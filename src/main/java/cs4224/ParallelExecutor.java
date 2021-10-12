package cs4224;

import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ParallelExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(ParallelExecutor.class);
    final ExecutorService executorService;
    List<Callable<Object>> tasks;

    public ParallelExecutor(ExecutorService executorService) {
        this.executorService = executorService;
        this.tasks = new ArrayList<>();
    }

    public ParallelExecutor addTask(final Callable<Object> callable) {
        this.tasks.add(callable);
        return this;
    }

    @SneakyThrows
    public List<Object> execute() {
        List<Future<Object>> futures = executorService.invokeAll(tasks);
        List<Object> taskResult = futures.stream().map(future -> {
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("Error while executing parallel tasks: ", e);
                return new Object();
            }
        }).collect(Collectors.toList());

        this.tasks = new ArrayList<>();
        return taskResult;
    }
}
