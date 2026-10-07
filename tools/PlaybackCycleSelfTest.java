import org.adskip.core.*;

public final class PlaybackCycleSelfTest {
    static final BiliVideoKey KEY = new BiliVideoKey("BV1xx411c7mD", "1");
    static SkipEngine.PlaybackSnapshot s(long pos,long now,long raw,long updated,float speed) {
        return new SkipEngine.PlaybackSnapshot("same",KEY,1,pos,382000,speed,true,true,now,raw,updated);
    }
    static void check(boolean x,String reason) { if(!x)throw new AssertionError(reason); }
    public static void main(String[] args) {
        PlaybackCycleGate gate=new PlaybackCycleGate();
        check(!gate.observe(s(300000,100000,106122,30000,2),false),"first snapshot");
        check(gate.observe(s(3000,142500,0,141000,2),false),"real 2x end-to-start");
        check(!gate.observe(s(4000,143000,0,141000,2),false),"same callback replayed");
        gate.clear();gate.observe(s(110000,100000,106122,97000,1),false);
        check(!gate.observe(s(73000,101000,72500,100500,1),false),"Undo mistaken for cycle");
        gate.clear();gate.observe(s(300000,100000,200000,50000,2),false);
        check(!gate.observe(s(1000,102000,0,101500,2),false),"ordinary rewind mistaken for loop");
        gate.clear();gate.observe(s(300000,100000,200000,50000,2),false);
        check(!gate.observe(s(2000,142000,-1,-1,2),false),"getter-only cycle");
        gate.clear();gate.observe(s(300000,100000,200000,50000,2),false);
        check(!gate.observe(s(12000,147000,0,141000,2),false),"stale raw callback");
        gate.clear();gate.observe(s(300000,100000,200000,50000,2),false);
        check(!gate.observe(s(3000,142500,0,141000,2),true),"pending seek reset");
        gate.clear();gate.observe(s(300000,100000,200000,50000,2),false);
        check(!gate.observe(s(3000,142500,0,141000,1),false),"speed change");
        gate.clear();gate.observe(s(300000,100000,200000,50000,2),false);
        check(!gate.observe(new SkipEngine.PlaybackSnapshot("other",KEY,1,3000,382000,2,true,true,142500,0,141000),false),"scope change");
        gate.clear();gate.observe(s(300000,100000,200000,50000,2),false);
        check(!gate.observe(s(0,99999,0,99998,2),false),"clock rollback");
        System.out.println("PASS playback-cycle raw evidence, timing, scope, speed and Undo boundaries");
    }
}
