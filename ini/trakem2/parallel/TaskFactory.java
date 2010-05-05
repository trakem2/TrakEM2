package ini.trakem2.parallel;

import java.util.concurrent.Callable;

public interface TaskFactory<I,O> {

	public Callable<O> create(final I input);
}
