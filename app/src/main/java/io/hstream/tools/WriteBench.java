package io.hstream.tools;

import io.hstream.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import com.google.common.util.concurrent.RateLimiter;
import picocli.CommandLine;

public class WriteBench {

    // private static final String serviceUrl = "localhost:6570";

    private static ExecutorService executorService;

    private static long lastReportTs;
    private static long lastReadSuccessAppends;
    private static long lastReadFailedAppends;

    private static AtomicLong successAppends = new AtomicLong();
    private static AtomicLong failedAppends = new AtomicLong();

    public static void main(String[] args) throws Exception{
        var options = new Options();
        var commandLine = new CommandLine(options).parseArgs(args);
        System.out.println(options);

        if(options.helpRequested) {
            CommandLine.usage(options, System.out);
            return;
        }

        HStreamClient client = HStreamClient.builder().serviceUrl(options.serviceUrl).build();

        // removeAllStreams(client);

        List<List<BufferedProducer>> producersPerThread = new ArrayList<>(options.threadCount);
        executorService = Executors.newFixedThreadPool(options.threadCount);
        RateLimiter rateLimiter = RateLimiter.create(options.rateLimit);

        for(int i = 0; i < options.streamCount; ) {
            List<BufferedProducer> bufferedProducers = new ArrayList<>(options.streamCount / options.threadCount);
            for(int j = 0; j < options.threadCount; ++j, ++i) {
                var streamName = options.streamNamePrefix + i;
                client.createStream(streamName);
                var bufferedProducer = client
                        .newBufferedProducer()
                        .stream(streamName)
                        .maxBytesSize(options.bufferSize)
                        .flushIntervalMs(options.flushMills)
                        .recordCountLimit(options.rateLimit * 2 / (1000 / options.flushMills)).build();
                bufferedProducers.add(bufferedProducer);
            }
            producersPerThread.add(bufferedProducers);
        }

        Random random = new Random();
        byte[] payload = new byte[options.recordSize];
        random.nextBytes(payload);
        Record record = Record.newBuilder().rawRecord(payload).build();

        lastReportTs = System.currentTimeMillis();
        lastReadSuccessAppends = 0;
        lastReadFailedAppends = 0;
        for(int i = 0; i < options.threadCount; ++i) {
            int index = i;
            executorService.submit(() -> {
                append(rateLimiter, producersPerThread.get(index), record);
            });
        }

        while(true) {
            Thread.sleep(options.reportIntervalSeconds * 1000);
            long now = System.currentTimeMillis();
            long successRead = successAppends.get();
            long failedRead = failedAppends.get();
            long duration = now - lastReportTs;
            double successPerSeconds = (double)(successRead - lastReadSuccessAppends) * 1000 / duration;
            double failurePerSeconds = (double)(failedRead - lastReadFailedAppends) * 1000 / duration;
            double throughput = (double)(successRead - lastReadSuccessAppends) * options.recordSize * 1000 / duration / 1024 / 1024;

            lastReportTs = now;
            lastReadSuccessAppends = successRead;
            lastReadFailedAppends = failedRead;

            System.out.println(String.format("[Append]: success %f record/s, failed %f record/s, throughput %f MB/s", successPerSeconds, failurePerSeconds, throughput));
        }
    }

    public static void append(RateLimiter rateLimiter, List<BufferedProducer> producers, Record record) {
        while(true) {

            for(var producer: producers) {
               rateLimiter.acquire();
               producer.write(record).handle((recordId, throwable) -> {
                    if(throwable != null) {
                        failedAppends.incrementAndGet();
                    } else {
                        successAppends.incrementAndGet();
                    }
                    return null;
               });
            }

            // Thread.yield();
        }

    }

    static void removeAllStreams(HStreamClient client) {
        var streams = client.listStreams();
        for(var stream: streams) {
            client.deleteStream(stream.getStreamName());
        }
    }

    static class Options {

        @CommandLine.Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message")
        boolean helpRequested = false;

        @CommandLine.Option(names = "--service-url")
        String serviceUrl = "192.168.0.216:6570";

        @CommandLine.Option(names = "--stream-name-prefix")
        String streamNamePrefix = "write_bench_stream_";

        @CommandLine.Option(names = "--record-size", description = "in bytes")
        int recordSize = 1024; // bytes

        @CommandLine.Option(names = "--time-trigger", description = "in ms")
        int flushMills = 10; // ms

        @CommandLine.Option(names = "--buffer-size", description = "in bytes")
        int bufferSize = 1024 * 1024; // bytes

        @CommandLine.Option(names = "--stream-count")
        int streamCount = 100;

        @CommandLine.Option(names = "--thread-count")
        int threadCount = 4;

        @CommandLine.Option(names = "--report-interval", description = "in seconds")
        int reportIntervalSeconds = 3;

        @CommandLine.Option(names = "--rate-limit")
        int rateLimit = 100000;

        @Override
        public String toString() {
            return "Options{" +
                    "helpRequested=" + helpRequested +
                    ", serviceUrl='" + serviceUrl + '\'' +
                    ", streamNamePrefix='" + streamNamePrefix + '\'' +
                    ", recordSize=" + recordSize +
                    ", flushMills=" + flushMills +
                    ", bufferSize=" + bufferSize +
                    ", streamCount=" + streamCount +
                    ", threadCount=" + threadCount +
                    ", reportIntervalSeconds=" + reportIntervalSeconds +
                    ", rateLimit=" + rateLimit +
                    '}';
        }
    }
}