package nodelast;

import arc.Core;
import arc.Events;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Unit;
import mindustry.input.InputHandler;
import mindustry.mod.Mod;
import mindustry.world.Block;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.power.PowerNode;

/**
 * Node Last (노드 마지막 건설)
 *
 * 건설 대기열에 노드류(PowerNode / BeamNode 계열)가 섞여 있어도,
 * 노드가 아닌 건설 계획을 전부 지은 뒤에 노드를 설치한다.
 * 철거 계획은 이 규칙에 영향을 주지 않는다.
 */
public class NodeLastMod extends Mod{
    /** 설정 키 */
    static final String PREF_ENABLED = "nodelast-enabled";
    static final String PREF_STRICT = "nodelast-strict";

    /** 엄격 모드 검사 주기 (tick) */
    static final int CHECK_INTERVAL = 5;

    /** 노드 클래스 밖의 블록(다른 모드의 노드 등)을 노드로 취급하려면 이름을 추가한다. */
    static final String[] EXTRA_NODE_NAMES = {};

    private int frame = 0;
    /** 이 모드가 건설을 일시정지시킨 상태인지 */
    private boolean autoHeld = false;

    public NodeLastMod(){
        Events.run(Trigger.update, () -> {
            try{
                tick();
            }catch(Throwable t){
                releaseHold();
            }
        });

        Events.on(WorldLoadEvent.class, e -> autoHeld = false);

        Events.on(ClientLoadEvent.class, e -> {
            try{
                Vars.ui.settings.addCategory("Node Last", t -> {
                    t.checkPref(PREF_ENABLED, true);
                    t.checkPref(PREF_STRICT, true);
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

    // ── 큐 재정렬 ──────────────────────────────────────────────

    /**
     * 큐 맨 앞이 노드 계획이고 노드가 아닌 건설 계획이 하나라도 남아 있으면,
     * 모든 노드 계획을 큐 뒤쪽으로 옮긴다. (노드끼리, 그 외 계획끼리 각자의 상대 순서는 유지)
     * 철거 계획은 "일반 건설 계획"으로 세지 않으며 노드 앞쪽 그룹에 그대로 남는다.
     */
    static void moveNodesBack(Queue<BuildPlan> plans){
        int n = plans.size;
        if(n < 2 || !isNodePlan(plans.first())) return;

        boolean hasOther = false;
        for(int i = 1; i < n; i++){
            if(isOtherBuildPlan(plans.get(i))){
                hasOther = true;
                break;
            }
        }
        if(!hasOther) return;

        // 안정 분할: 노드가 아닌 계획은 그대로 다시 넣고, 노드는 모아 두었다가 맨 뒤에 붙인다.
        Seq<BuildPlan> nodes = new Seq<>();
        for(int i = 0; i < n; i++){
            BuildPlan p = plans.removeFirst();
            if(isNodePlan(p)) nodes.add(p);
            else plans.addLast(p);
        }
        for(BuildPlan p : nodes) plans.addLast(p);
    }

    // ── 건설 일시정지 제어 ─────────────────────────────────────

    private void releaseHold(){
        if(autoHeld){
            autoHeld = false;
            try{
                Vars.control.input.isBuilding = true;
            }catch(Throwable ignored){}
        }
    }

    /** 일반 건설 계획이 남아 있는데 전부 건설 범위 밖이면 true (철거 계획은 무시) */
    private boolean needHold(Unit unit, Queue<BuildPlan> plans){
        boolean infinite = Vars.state.rules.infiniteResources || Vars.player.team().rules().infiniteResources;
        if(infinite) return false; // 무한 자원 모드는 범위 제한이 없다

        float range = unit.type().buildRange > 0 ? unit.type().buildRange : Vars.buildingRange;

        boolean anyOther = false;
        for(int i = 0; i < plans.size; i++){
            BuildPlan p = plans.get(i);
            if(!isOtherBuildPlan(p)) continue;
            anyOther = true;
            if(unit.within(p.x * Vars.tilesize, p.y * Vars.tilesize, range)) return false;
        }
        return anyOther;
    }

    // ── 매 프레임 ──────────────────────────────────────────────

    private void tick(){
        if(Vars.headless || Vars.player == null || !Vars.state.isGame()) return;

        if(!Core.settings.getBool(PREF_ENABLED, true)){
            releaseHold();
            return;
        }

        if(Vars.player.dead()){
            releaseHold();
            return;
        }
        Unit unit = Vars.player.unit();
        if(unit == null){
            releaseHold();
            return;
        }

        Queue<BuildPlan> plans = unit.plans();
        int n = plans.size;
        if(n == 0){
            releaseHold();
            return;
        }

        // 1) 맨 앞이 노드이고 일반 건설 계획이 남아 있으면, 노드를 뒤로 보낸다.
        moveNodesBack(plans);

        // 2) 엄격 모드: 일반 건설 계획이 전부 범위 밖이면 범위 안 노드를 짓지 않도록 건설을 잠시 멈춘다.
        if(Core.settings.getBool(PREF_STRICT, true)){
            if((frame++ % CHECK_INTERVAL) != 0) return;

            InputHandler input = Vars.control.input;
            if(needHold(unit, plans)){
                if(input.isBuilding){
                    input.isBuilding = false;
                    autoHeld = true;
                }
            }else{
                releaseHold();
            }
        }else{
            releaseHold();
        }
    }
}
