/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.seednode;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.bitsquare.app.BitsquareEnvironment;
import io.bitsquare.app.BitsquareExecutable;
import io.bitsquare.app.CoreOptionKeys;
import io.bitsquare.common.UserThread;
import io.bitsquare.common.util.Profiler;
import io.bitsquare.common.util.RestartUtil;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.bitcoinj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static io.bitsquare.app.BitsquareEnvironment.DEFAULT_APP_NAME;
import static io.bitsquare.app.BitsquareEnvironment.DEFAULT_USER_DATA_DIR;

public class SeedNodeMain extends BitsquareExecutable {
    private static final Logger log = LoggerFactory.getLogger(SeedNodeMain.class);
    private static long MAX_MEMORY_MB_DEFAULT = 500;
    private static final long CHECK_MEMORY_PERIOD_SEC = 5 * 60;
    private SeedNode seedNode;
    private volatile boolean stopped;
    private static long maxMemory = MAX_MEMORY_MB_DEFAULT;

    public static void main(String[] args) throws Exception {
        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("SeedNodeMain")
                .setDaemon(true)
                .build();
        UserThread.setExecutor(Executors.newSingleThreadExecutor(threadFactory));

        // We don't want to do the full argument parsing here as that might easily change in update versions
        // So we only handle the absolute minimum which is APP_NAME, APP_DATA_DIR_KEY and USER_DATA_DIR
        BitsquareEnvironment.setDefaultAppName("Bitsquare_seednode");
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        parser.accepts(CoreOptionKeys.USER_DATA_DIR_KEY, description("User data directory", DEFAULT_USER_DATA_DIR))
                .withRequiredArg();
        parser.accepts(CoreOptionKeys.APP_NAME_KEY, description("Application name", DEFAULT_APP_NAME))
                .withRequiredArg();

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException ex) {
            System.out.println("error: " + ex.getMessage());
            System.out.println();
            parser.printHelpOn(System.out);
            System.exit(EXIT_FAILURE);
            return;
        }
        BitsquareEnvironment bitsquareEnvironment = new BitsquareEnvironment(options);

        // need to call that before BitsquareAppMain().execute(args)
        BitsquareExecutable.initAppDir(bitsquareEnvironment.getProperty(CoreOptionKeys.APP_DATA_DIR_KEY));

        // For some reason the JavaFX launch process results in us losing the thread context class loader: reset it.
        // In order to work around a bug in JavaFX 8u25 and below, you must include the following code as the first line of your realMain method:
        Thread.currentThread().setContextClassLoader(SeedNodeMain.class.getClassLoader());

        new SeedNodeMain().execute(args);
    }

    @Override
    protected void doExecute(OptionSet options) {
        final BitsquareEnvironment environment = new BitsquareEnvironment(options);
        SeedNode.setEnvironment(environment);

        UserThread.execute(() -> seedNode = new SeedNode());

        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            if (throwable.getCause() != null && throwable.getCause().getCause() != null &&
                    throwable.getCause().getCause() instanceof BlockStoreException) {
                log.error(throwable.getMessage());
            } else {
                log.error("Uncaught Exception from thread " + Thread.currentThread().getName());
                log.error("throwableMessage= " + throwable.getMessage());
                log.error("throwableClass= " + throwable.getClass());
                log.error("Stack trace:\n" + ExceptionUtils.getStackTrace(throwable));
                throwable.printStackTrace();
                log.error("We shut down the app because an unhandled error occurred");
                // We don't use the restart as in case of OutOfMemory errors the restart might fail as well
                // The run loop will restart the node anyway...
                System.exit(EXIT_FAILURE);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);

        String maxMemoryOption = environment.getProperty(CoreOptionKeys.MAX_MEMORY);
        if (maxMemoryOption != null && !maxMemoryOption.isEmpty()) {
            try {
                maxMemory = Integer.parseInt(maxMemoryOption);
            } catch (Throwable t) {
                log.error(t.getMessage());
            }
        }

        UserThread.runPeriodically(() -> {
            Profiler.printSystemLoad(log);
            long usedMemoryInMB = Profiler.getUsedMemoryInMB();
            if (!stopped) {
                if (usedMemoryInMB > (maxMemory - 100)) {
                    log.warn("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                                    "We are over our memory warn limit and call the GC. usedMemoryInMB: {}" +
                                    "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n\n",
                            usedMemoryInMB);
                    System.gc();
                    usedMemoryInMB = Profiler.getUsedMemoryInMB();
                    Profiler.printSystemLoad(log);
                }

                final long finalUsedMemoryInMB = usedMemoryInMB;
                UserThread.runAfter(() -> {
                    if (finalUsedMemoryInMB > maxMemory)
                        restart(environment);
                }, 1);
            }
        }, CHECK_MEMORY_PERIOD_SEC);

        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ignore) {
            }
        }
    }

    private void restart(BitsquareEnvironment environment) {
        stopped = true;
        seedNode.gracefulShutDown(() -> {
            try {
                final String[] tokens = environment.getAppDataDir().split("_");
                String logPath = "error_" + (tokens.length > 1 ? tokens[tokens.length - 2] : "") + ".log";
                RestartUtil.restartApplication(logPath);
            } catch (IOException e) {
                log.error(e.toString());
                e.printStackTrace();
            } finally {
                log.warn("Shutdown complete");
                System.exit(0);
            }
        });
    }
}
