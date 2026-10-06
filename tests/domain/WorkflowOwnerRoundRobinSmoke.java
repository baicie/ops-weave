import com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin;
import com.acme.opsweave.integration.domain.WorkflowFailure;

/** Deterministic fairness fixture; it does not claim distributed scheduling. */
public final class WorkflowOwnerRoundRobinSmoke {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Owner rotation check "+checks);}
    public static void main(String[] args){
        var queue=new WorkflowOwnerRoundRobin<String>(3);
        check(queue.next()==null);
        queue.remember("tenant-a/owner-a");queue.remember("tenant-b/owner-b");queue.remember("tenant-c/owner-c");
        queue.remember("tenant-a/owner-a");
        check(queue.next().equals("tenant-a/owner-a"));
        check(queue.next().equals("tenant-b/owner-b"));
        check(queue.next().equals("tenant-c/owner-c"));
        check(queue.next().equals("tenant-a/owner-a"));
        check(queue.remove("tenant-b/owner-b")&&queue.size()==2);
        check(queue.next().equals("tenant-c/owner-c"));
        check(queue.next().equals("tenant-a/owner-a"));
        queue.remember("tenant-d/owner-d");
        try {queue.remember("tenant-e/owner-e");throw new AssertionError("Expected bounded owner rotation");}
        catch(WorkflowFailure expected){check(expected.code()==WorkflowFailure.Code.CAPACITY);}
        check(queue.remove("tenant-c/owner-c")&&queue.remove("tenant-a/owner-a")&&queue.remove("tenant-d/owner-d")&&queue.next()==null);
        System.out.println("WorkflowOwnerRoundRobinSmoke: "+checks+" checks passed");
    }
}
