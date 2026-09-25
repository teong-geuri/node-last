package nodelast;

import arc.Core;
import arc.Events;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.graphics.g2d.Draw;
import arc.input.KeyCode;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.mod.Mod;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.power.PowerNode;

/**
 * Node Last (노드 마지막 건설)
 *
 * 건설 대기열에 노드가 아닌 건설 계획이 하나라도 남아 있는 동안에는(건설 범위 안팎 무관),
 * 노드류(PowerNode / BeamNode 계열) 계획을 대기열에서 잠시 빼서 모드가 보관한다.
 * 노드가 아닌 계획이 전부 끝나면 보관해 둔 노드 계획을 원래 순서대로 대기열에 돌려놓는다.
 *
 * - 빌더는 대기열에 있는 계획만 짓기 때문에, 범위 밖이거나 자원이 부족해서 일반 건물이 건너뛰어져도
 *   노드가 그 틈에 먼저 지어지지 않는다.
 * - 건설 일시정지(E키)는 전혀 사용하지 않는다.
 * - 철거 계획은 이 규칙에 영향을 주지 않는다.
 * - 사용자가 건설을 취소하면(대기열이 비면) 보관 중인 노드도 함께 버린다.
 */
public class NodeLastMod extends Mod{
    static final String PREF_ENABLED = "nodelast-enabled";
    static final String PREF_GHOSTS = "nodelast-ghosts";
    /** 모바일 전용: 이 토글이 켜져 있으면 G/N 키를 누르고 있는 것과 같은 효과 (원래 순서로 되돌림) */
    static final String PREF_BYPASS_MOBILE = "nodelast-bypass-mobile";

    /** 이 키를 누르고 있는 동안에는 원래(바닐라) 순서로 되돌아간다. (PC 전용)
     *  G는 게임 기본 단축키(전체 유닛 선택)와 겹치지만, 그 기능은 커맨드 모드(Shift)를 함께 누르고 있을 때만
     *  작동하므로 평소에 그냥 눌러서는 다른 동작을 일으키지 않는다. */
    static final KeyCode[] BYPASS_KEYS = {KeyCode.g};

    /** 보관 중인 노드 청사진을 그릴 때의 투명도 */
    static final float WAITING_ALPHA = 0.5f;

    /** 노드 클래스 밖의 블록(다른 모드의 노드 등)을 노드로 취급하려면 이름을 추가한다. */
    static final String[] EXTRA_NODE_NAMES = {};

    /** 보관 중인 노드 계획 (원래 순서 유지) */
    private final Seq<BuildPlan> stash = new Seq<>();
    /** 지난 프레임에 대기열에 있던 "노드가 아닌 건설 계획" (완료/취소 판별용) */
    private final Seq<BuildPlan> lastOthers = new Seq<>();
    /** 보관 중인 계획의 주인 유닛 */
    private Unit owner;
    private boolean drawFailed = false;

    public NodeLastMod(){
        Events.run(Trigger.update, () -> {
            try{
                tick();
            }catch(Throwable t){
                Log.err("[node-last] tick 오류 - 보관 중인 노드를 대기열로 돌려놓습니다", t);
                try{ giveBack(); }catch(Throwable ignored){}
            }
        });

        Events.run(Trigger.draw, this::drawWaiting);

        Events.on(WorldLoadEvent.class, e -> reset());

        Events.on(ClientLoadEvent.class, e -> {
            try{
                Vars.ui.settings.addCategory("Node Last", t -> {
                    t.checkPref(PREF_ENABLED, true);
                    t.checkPref(PREF_GHOSTS, true);
                    if(Vars.android) t.checkPref(PREF_BYPASS_MOBILE, false);
                });
            }catch(Throwable t){
                Log.err("[node-last] 설정 메뉴 등록 실패", t);
            }
        });
    }

    // ── 판별 ─────────────────────────────────────────────────

    static boolean isNodeBlock(Block block){
        if(block == null) return false;
        if(block instanceof PowerNode || block instanceof BeamNode) return true;
        for(String name : EXTRA_NODE_NAMES){
            if(name.equals(block.name)) return true;
        }
        return false;
    }

    /** 노드 건설 계획인가 (철거 계획 제외) */
    static boolean isNodePlan(BuildPlan plan){
        return plan != null && !plan.breaking && isNodeBlock(plan.block);
    }

    /** 노드가 기다려야 하는 일반 건설 계획인가 (철거 계획과 노드는 제외) */
    static boolean isOtherBuildPlan(BuildPlan plan){
        return plan != null && !plan.breaking && !isNodeBlock(plan.block);
    }

    /** 이 계획의 블록이 이미 실제로 지어져 있는가 */
    static boolean isBuilt(BuildPlan plan){
        Tile t = plan.tile();
        return t != null && t.block() == plan.block;
    }

    static boolean allBuilt(Seq<BuildPlan> plans){
        for(int i = 0; i < plans.size; i++){
            if(!isBuilt(plans.get(i))) return false;
        }
        return true;
    }

    // ── 보관 / 복구 ────────────────────────────────────────────

    /** 대기열의 노드 계획을 순서를 유지한 채 전부 보관함으로 옮긴다. (노드가 아닌 계획의 순서도 그대로) */
    static void extractNodes(Queue<BuildPlan> q, Seq<BuildPlan> into){
        int n = q.size;
        for(int i = 0; i < n; i++){
            BuildPlan p = q.removeFirst();
            if(isNodePlan(p)) into.add(p);
            else q.addLast(p);
        }
    }

    private void restore(Queue<BuildPlan> q){
        for(int i = 0; i < stash.size; i++) q.addLast(stash.get(i));
        stash.clear();
    }

    private void reset(){
        stash.clear();
        lastOthers.clear();
        owner = null;
    }

    /** 오류 등 비상시: 보관 중인 노드를 현재 유닛의 대기열로 돌려놓고 상태를 비운다. */
    private void giveBack(){
        if(!stash.isEmpty() && owner != null && Vars.player != null && Vars.player.unit() == owner){
            restore(owner.plans());
        }
        reset();
    }

    /**
     * 지금 이 순간 "원래(바닐라) 순서로 되돌리기"가 활성 상태인가.
     * PC: G 키를 누르고 있는 동안만 (게임 기본 단축키인 전체 유닛 선택과 겹치지만, 그 기능은 Shift와
     * 함께 눌러야 커맨드 모드에서 작동하므로 평소 사용에는 지장이 없다).
     * 모바일: 키 입력이 없으므로 대신 설정의 토글 값을 그대로 쓴다.
     */
    static boolean isBypassed(){
        try{
            if(Vars.android) return Core.settings.getBool(PREF_BYPASS_MOBILE, false);
            if(Core.input == null) return false;
            for(KeyCode key : BYPASS_KEYS){
                if(Core.input.keyDown(key)) return true;
            }
            return false;
        }catch(Throwable t){
            return false;
        }
    }

    // ── 매 프레임 ──────────────────────────────────────────────

    private void tick(){
        if(Vars.headless || Vars.player == null || !Vars.state.isGame()) return;

        Unit unit = Vars.player.dead() ? null : Vars.player.unit();
        if(unit == null){
            reset(); // 유닛이 사라지면 대기열도 사라진 것이므로 보관분도 폐기
            return;
        }
        if(owner != null && owner != unit) reset(); // 다른 유닛으로 바뀌면 이전 보관분 폐기

        Queue<BuildPlan> q = unit.plans();

        if(!Core.settings.getBool(PREF_ENABLED, true) || isBypassed()){
            // 모드를 끄거나(설정) 우회 중이면(키 또는 모바일 토글), 보관 중이던 노드를 즉시 대기열로 돌려놓는다.
            if(!stash.isEmpty() && owner == unit) restore(q);
            reset();
            return;
        }

        boolean hasOther = false, hasNode = false;
        for(int i = 0; i < q.size; i++){
            BuildPlan p = q.get(i);
            if(isNodePlan(p)) hasNode = true;
            else if(isOtherBuildPlan(p)) hasOther = true;
        }

        if(hasOther){
            // 일반 건설 계획이 남아 있음 → 노드 계획은 전부 보관
            owner = unit;
            if(hasNode) extractNodes(q, stash);

            lastOthers.clear();
            for(int i = 0; i < q.size; i++){
                BuildPlan p = q.get(i);
                if(isOtherBuildPlan(p)) lastOthers.add(p);
            }
        }else{
            // 일반 건설 계획이 더 이상 없음
            if(!stash.isEmpty()){
                // 마지막 일반 계획들이 실제로 "지어져서" 사라졌으면 완료 → 노드를 돌려놓는다.
                // 지어지지 않은 채 사라졌으면 사용자가 취소한 것 → 보관 중인 노드도 버린다.
                if(owner == unit && allBuilt(lastOthers)) restore(q);
                else stash.clear();
            }
            lastOthers.clear();
            owner = null;
        }
    }

    // ── 보관 중인 노드 청사진 표시 ──────────────────────────────

    private void drawWaiting(){
        if(drawFailed || Vars.headless || stash.isEmpty() || owner == null || Vars.player == null) return;
        try{
            if(!Core.settings.getBool(PREF_ENABLED, true) || !Core.settings.getBool(PREF_GHOSTS, true)) return;
            Unit unit = Vars.player.unit();
            if(unit != owner) return;

            Core.camera.bounds(Tmp.r1);
            Draw.z(Layer.plans);
            for(int i = 0; i < stash.size; i++){
                BuildPlan p = stash.get(i);
                if(p.block == null) continue;
                Tmp.r2.setCentered(p.drawx(), p.drawy(), p.block.size * 8f + 16f);
                if(!Tmp.r1.overlaps(Tmp.r2)) continue;
                unit.drawPlan(p, WAITING_ALPHA);
                unit.drawPlanTop(p, WAITING_ALPHA);
            }
            Draw.reset();
        }catch(Throwable t){
            drawFailed = true; // 그리기 오류가 매 프레임 반복되지 않도록 한 번만 기록하고 끈다
            Log.err("[node-last] 대기 중 노드 표시 오류 - 표시 기능을 끕니다", t);
        }
    }
}
