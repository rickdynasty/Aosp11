/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.invoker;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.CommandLineBuildInfoBuilder;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.CommandRunner.ExitCode;
import com.android.tradefed.command.CommandScheduler;
import com.android.tradefed.command.ICommandScheduler.IScheduledInvocationListener;
import com.android.tradefed.config.ArgsOptionParser;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.DynamicRemoteFileResolver;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.proxy.AutomatedReporters;
import com.android.tradefed.config.proxy.TradefedDelegator;
import com.android.tradefed.device.DeviceManager;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.FreeDeviceState;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.device.RemoteAndroidDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.TcpDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.device.cloud.ManagedRemoteDevice;
import com.android.tradefed.device.cloud.NestedRemoteDevice;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.error.IHarnessException;
import com.android.tradefed.guice.InvocationScope;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.InvocationInfo;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.logger.TfObjectTracker;
import com.android.tradefed.invoker.sandbox.ParentSandboxInvocationExecution;
import com.android.tradefed.invoker.sandbox.SandboxedInvocationExecution;
import com.android.tradefed.invoker.shard.LastShardDetector;
import com.android.tradefed.invoker.shard.ShardHelper;
import com.android.tradefed.log.BaseLeveledLogOutput;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.ILogRegistry;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.log.StdoutLogger;
import com.android.tradefed.postprocessor.IPostProcessor;
import com.android.tradefed.result.ActionInProgress;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogSaverResultForwarder;
import com.android.tradefed.result.ReportPassedTests;
import com.android.tradefed.result.ResultAndLogForwarder;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.retry.IRetryDecision;
import com.android.tradefed.retry.ResultAggregator;
import com.android.tradefed.retry.RetryStrategy;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.DeviceFailedToBootError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.PrettyPrintDelimiter;
import com.android.tradefed.util.QuotationAwareTokenizer;
import com.android.tradefed.util.RunInterruptedException;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TimeUtil;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of {@link ITestInvocation}.
 * <p/>
 * Loads major objects based on {@link IConfiguration}
 *   - retrieves build
 *   - prepares target
 *   - runs tests
 *   - reports results
 */
public class TestInvocation implements ITestInvocation {

    /** Key of the command line args attributes */
    public static final String COMMAND_ARGS_KEY = "command_line_args";

    /**
     * Format of the key in {@link IBuildInfo} to log the battery level for each step of the
     * invocation. (Setup, test, tear down).
     */
    private static final String BATTERY_ATTRIBUTE_FORMAT_KEY = "%s-battery-%s";

    public static final String TRADEFED_LOG_NAME = "host_log";
    public static final String TRADEFED_END_HOST_LOG = "end_host_log";
    private static final String TRADEFED_DELEGATED_LOG_NAME = "delegated_parent_log";
    public static final String TRADEFED_CONFIG_NAME = "tradefed-expanded-config";
    /** Suffix used on host_log for the part before sharding occurs. */
    static final String BEFORE_SHARDING_SUFFIX = "_before_sharding";
    static final String DEVICE_LOG_NAME_PREFIX = "device_logcat_";
    static final String EMULATOR_LOG_NAME_PREFIX = "emulator_log_";
    static final String BUILD_ERROR_BUGREPORT_NAME = "build_error_bugreport";
    static final String DEVICE_UNRESPONSIVE_BUGREPORT_NAME = "device_unresponsive_bugreport";
    static final String INVOCATION_ENDED_BUGREPORT_NAME = "invocation_ended_bugreport";
    static final String TARGET_SETUP_ERROR_BUGREPORT_NAME = "target_setup_error_bugreport";
    static final String BATT_TAG = "[battery level]";
    static final String RECOVERY_LOG_DEVICE_PATH = "/tmp/recovery.log";

    public enum Stage {
        ERROR("error"),
        SETUP("setup"),
        TEST("test"),
        TEARDOWN("teardown");

        private final String mName;

        Stage(String name) {
            mName = name;
        }

        public String getName() {
            return mName;
        }
    }

    /** The different mode an invocation can run into. */
    public enum RunMode {
        REGULAR,
        PARENT_SANDBOX,
        SANDBOX,
        REMOTE_INVOCATION,
        DELEGATED_INVOCATION
    }

    private String mStatus = "(not invoked)";
    private String mStopCause = null;
    private Long mStopRequestTime = null;
    private boolean mTestStarted = false;
    private boolean mInvocationFailed = false;
    private boolean mDelegatedInvocation = false;
    private List<IScheduledInvocationListener> mSchedulerListeners = new ArrayList<>();
    private DeviceUnavailableMonitor mUnavailableMonitor = new DeviceUnavailableMonitor();
    private ExitCode mExitCode = ExitCode.NO_ERROR;
    private Throwable mExitStack = null;

    /**
     * Display a log message informing the user of a invocation being started.
     *
     * @param context the {@link IInvocationContext}
     * @param config the {@link IConfiguration}
     */
    private void logStartInvocation(IInvocationContext context, IConfiguration config) {
        String shardSuffix = "";
        if (config.getCommandOptions().getShardIndex() != null) {
            shardSuffix =
                    String.format(
                            " (shard %d of %d)",
                            config.getCommandOptions().getShardIndex() + 1,
                            config.getCommandOptions().getShardCount());
        }
        StringBuilder buildInfos = new StringBuilder();
        StringBuilder msg = new StringBuilder("Starting invocation for '");
        msg.append(context.getTestTag());
        msg.append("' with ");
        for (Entry<ITestDevice, IBuildInfo> entry : context.getDeviceBuildMap().entrySet()) {
            msg.append("'[ ");
            msg.append(entry.getValue().toString());
            buildInfos.append(entry.getValue().toString());
            msg.append(" on device '");
            msg.append(entry.getKey().getSerialNumber());
            msg.append("'] ");
        }
        msg.append(shardSuffix);
        CLog.logAndDisplay(LogLevel.INFO, msg.toString());
        mStatus = String.format("running %s on build(s) '%s'", context.getTestTag(),
                buildInfos.toString()) + shardSuffix;
    }

    /**
     * Performs the invocation
     *
     * @param config the {@link IConfiguration}
     * @param testInfo the {@link TestInformation} to use for the invocation.
     */
    private void performInvocation(
            IConfiguration config,
            TestInformation testInfo,
            IInvocationExecution invocationPath,
            ITestInvocationListener listener,
            boolean devicePreSetupDone)
            throws Throwable {
        ReportHostLog reportThread = new ReportHostLog(listener, config);
        Runtime.getRuntime().addShutdownHook(reportThread);
        String bugreportName = null;
        long startTime = System.currentTimeMillis();
        long elapsedTime = -1;
        Throwable exception = null;
        Throwable tearDownException = null;
        ITestDevice badDevice = null;
        IInvocationContext context = testInfo.getContext();

        // Ensure that no unexpected attributes are added afterward
        ((InvocationContext) context).lockAttributes();
        try {
            logDeviceBatteryLevel(context, "initial");
            // Run the preInvocationSetup on devices.
            if (!devicePreSetupDone) {
                if (!config.getCommandOptions().shouldUseSandboxing()) {
                    invocationPath.runDevicePreInvocationSetup(context, config, listener);
                }
            }
            // Then run the regular setup and run
            prepareAndRun(config, testInfo, invocationPath, listener);
        } catch (BuildError e) {
            exception = e;
            CLog.w("BuildError on device '%s'. Reason: %s", e.getDeviceSerial(), e.toString());
            bugreportName = BUILD_ERROR_BUGREPORT_NAME;
            if (e.getDeviceSerial() != null) {
                badDevice = context.getDeviceBySerial(e.getDeviceSerial());
            }
            if (e instanceof DeviceFailedToBootError) {
                if (badDevice == null) {
                    context.setRecoveryModeForAllDevices(RecoveryMode.NONE);
                } else {
                    badDevice.setRecoveryMode(RecoveryMode.NONE);
                }
            }
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
        } catch (TargetSetupError e) {
            exception = e;
            CLog.e("Caught exception while running invocation");
            CLog.e(e);
            bugreportName = TARGET_SETUP_ERROR_BUGREPORT_NAME;
            if (e.getDeviceSerial() != null) {
                badDevice = context.getDeviceBySerial(e.getDeviceSerial());
            }
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
        } catch (DeviceNotAvailableException e) {
            exception = e;
            // log a warning here so its captured before reportLogs is called
            CLog.w(
                    "Invocation did not complete due to device %s becoming not available. "
                            + "Reason: %s",
                    e.getSerial(), e.toString());
            badDevice = context.getDeviceBySerial(e.getSerial());
            if ((e instanceof DeviceUnresponsiveException) && badDevice != null
                    && TestDeviceState.ONLINE.equals(badDevice.getDeviceState())) {
                // under certain cases it might still be possible to grab a bugreport
                bugreportName = DEVICE_UNRESPONSIVE_BUGREPORT_NAME;
            }
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
            // Upon reaching here after an exception, it is safe to assume that recovery
            // has already been attempted so we disable it to avoid re-entry during clean up.
            if (badDevice != null) {
                badDevice.setRecoveryMode(RecoveryMode.NONE);
            }
            throw e;
        } catch (RunInterruptedException e) {
            exception = e;
            CLog.w("Invocation interrupted");
            // if a stop cause was set, the interruption is most likely due to the invocation being
            // cancelled
            if (mStopCause == null) {
                reportFailure(createFailureFromException(e, FailureStatus.UNSET), listener);
            }
        } catch (AssertionError e) {
            exception = e;
            CLog.e("Caught AssertionError while running invocation: %s", e.toString());
            CLog.e(e);
            reportFailure(createFailureFromException(e, FailureStatus.UNSET), listener);
        } catch (Throwable t) {
            exception = t;
            // log a warning here so its captured before reportLogs is called
            CLog.e("Unexpected exception when running invocation: %s", t.toString());
            CLog.e(t);
            reportFailure(createFailureFromException(t, FailureStatus.UNSET), listener);
            throw t;
        } finally {
            // Only capture logcat for TEST if we started the test phase.
            if (mTestStarted) {
                for (ITestDevice device : context.getDevices()) {
                    invocationPath.reportLogs(device, listener, Stage.TEST);
                }
            }
            CurrentInvocation.setActionInProgress(ActionInProgress.TEAR_DOWN);
            getRunUtil().allowInterrupt(false);
            if (!mDelegatedInvocation) {
                if (config.getCommandOptions().takeBugreportOnInvocationEnded()
                        || config.getCommandOptions().takeBugreportzOnInvocationEnded()) {
                    if (bugreportName != null) {
                        CLog.i("Bugreport to be taken for failure instead of invocation ended.");
                    } else {
                        bugreportName = INVOCATION_ENDED_BUGREPORT_NAME;
                    }
                }
                if (bugreportName != null) {
                    if (context.getDevices().size() == 1 || badDevice != null) {
                        ITestDevice collectBugreport = badDevice;
                        if (collectBugreport == null) {
                            collectBugreport = context.getDevices().get(0);
                        }
                        // If we have identified a faulty device only take the bugreport on it.
                        takeBugreport(collectBugreport, listener, bugreportName);
                    } else if (context.getDevices().size() > 1) {
                        ParallelDeviceExecutor<Boolean> executor =
                                new ParallelDeviceExecutor<>(context.getDevices().size());
                        List<Callable<Boolean>> callableTasks = new ArrayList<>();
                        final String reportName = bugreportName;
                        for (ITestDevice device : context.getDevices()) {
                            Callable<Boolean> callableTask =
                                    () -> {
                                        takeBugreport(device, listener, reportName);
                                        return true;
                                    };
                            callableTasks.add(callableTask);
                        }
                        // Capture the bugreports best effort, ignore the results.
                        executor.invokeAll(callableTasks, 5, TimeUnit.MINUTES);
                    }
                }
                reportRecoveryLogs(context.getDevices(), listener);
                if (exception == null) {
                    CLog.d("Checking that devices are online.");
                    checkDevicesAvailable(context.getDevices(), listener);
                } else {
                    CLog.d("Skip online check as an exception was already reported: %s", exception);
                }
            }
            // Save the device executeShellCommand logs
            logExecuteShellCommand(context.getDevices(), listener);

            mStatus = "tearing down";
            try {
                invocationPath.doTeardown(testInfo, config, listener, exception);
            } catch (Throwable e) {
                tearDownException = e;
                CLog.e("Exception when tearing down invocation: %s", tearDownException.toString());
                CLog.e(tearDownException);
                if (exception == null) {
                    // only report when the exception is new during tear down
                    reportFailure(
                            createFailureFromException(
                                    tearDownException, FailureStatus.INFRA_FAILURE),
                            listener);
                }
            }
            // Capture last logcat before releasing the device.
            for (ITestDevice device : context.getDevices()) {
                invocationPath.reportLogs(device, listener, Stage.TEARDOWN);
            }
            mStatus = "done running tests";
            CurrentInvocation.setActionInProgress(ActionInProgress.FREE_RESOURCES);
            // Log count of allocated devices for test accounting
            addInvocationMetric(InvocationMetricKey.DEVICE_COUNT, context.getNumDevicesAllocated());
            // Track the timestamp when we are done with devices
            addInvocationMetric(
                    InvocationMetricKey.DEVICE_DONE_TIMESTAMP, System.currentTimeMillis());
            if (exception == null) {
                exception = mUnavailableMonitor.getUnavailableException();
                if (exception != null) {
                    CLog.e("Found a test level only device unavailable exception:");
                    CLog.e(exception);
                }
            }
            Map<ITestDevice, FreeDeviceState> devicesStates =
                    handleAndLogReleaseState(context, exception, tearDownException);
            if (config.getCommandOptions().earlyDeviceRelease()) {
                context.markReleasedEarly();
                for (IScheduledInvocationListener scheduleListener : mSchedulerListeners) {
                    scheduleListener.releaseDevices(context, devicesStates);
                }
            }
            try {
                // Clean up host.
                invocationPath.doCleanUp(context, config, exception);
                if (mStopCause != null) {
                    String message =
                            String.format(
                                    "Invocation was interrupted due to: %s, results will be "
                                            + "affected.",
                                    mStopCause);
                    FailureDescription failure =
                            FailureDescription.create(message, FailureStatus.CANCELLED);
                    failure.setErrorIdentifier(InfraErrorIdentifier.INVOCATION_CANCELLED);
                    failure.setCause(
                            new HarnessRuntimeException(
                                    message, InfraErrorIdentifier.INVOCATION_CANCELLED));
                    reportFailure(failure, listener);
                    PrettyPrintDelimiter.printStageDelimiter(message);
                    if (mStopRequestTime != null) {
                        // This is not 100% perfect since result reporting can still run a bit
                        // longer, but this is our last opportunity to report it.
                        long latency = System.currentTimeMillis() - mStopRequestTime;
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.SHUTDOWN_HARD_LATENCY, latency);
                    }
                }
                reportHostLog(listener, config);
                // If host_log is reported, remove the hook
                Runtime.getRuntime().removeShutdownHook(reportThread);

                // Measure teardown disk usage before clean up
                Long size = measureWorkFolderSize(config, testInfo);
                if (size != null) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.TEAR_DOWN_DISK_USAGE, size);
                }
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.INVOCATION_END, System.currentTimeMillis());
                elapsedTime = System.currentTimeMillis() - startTime;
                reportInvocationEnded(config, context, listener, elapsedTime);
            } finally {
                TfObjectTracker.clearTracking();
                CurrentInvocation.clearInvocationInfos();
            }
        }
        if (tearDownException != null) {
            // this means a DNAE or RTE has happened during teardown, need to throw
            // if there was a preceding RTE or DNAE stored in 'exception', it would have already
            // been thrown before exiting the previous try...catch...finally block
            throw tearDownException;
        }
    }

    /** Do setup and run the tests */
    private void prepareAndRun(
            IConfiguration config,
            TestInformation testInfo,
            IInvocationExecution invocationPath,
            ITestInvocationListener listener)
            throws Throwable {
        getRunUtil().allowInterrupt(true);
        logDeviceBatteryLevel(testInfo.getContext(), "initial -> setup");
        CurrentInvocation.setActionInProgress(ActionInProgress.SETUP);
        invocationPath.doSetup(testInfo, config, listener);
        logDeviceBatteryLevel(testInfo.getContext(), "setup -> test");
        mTestStarted = true;
        CurrentInvocation.setActionInProgress(ActionInProgress.TEST);
        invocationPath.runTests(testInfo, config, listener);
        logDeviceBatteryLevel(testInfo.getContext(), "after test");
        CurrentInvocation.setActionInProgress(ActionInProgress.UNSET);
    }

    /**
     * Starts the invocation.
     * <p/>
     * Starts logging, and informs listeners that invocation has been started.
     *
     * @param config
     * @param context
     */
    private void startInvocation(IConfiguration config, IInvocationContext context,
            ITestInvocationListener listener) {
        logStartInvocation(context, config);
        listener.invocationStarted(context);
        logExpandedConfiguration(config, listener);
    }

    /** Report the exception failure as an invocation failure. */
    private void reportFailure(FailureDescription failure, ITestInvocationListener listener) {
        if (mInvocationFailed) {
            CLog.e("An invocation failure was already reported, ignoring %s", failure);
            return;
        }
        // Always report the failure
        listener.invocationFailed(failure);
        mInvocationFailed = true;
    }

    /**
     * Create a {@link FailureDescription} from an invocation exception.
     *
     * @param exception The exception to convert
     * @param defaultStatus The status to use by default if the exception is not a {@link
     *     IHarnessException}.
     */
    private FailureDescription createFailureFromException(
            Throwable exception, FailureStatus defaultStatus) {
        ErrorIdentifier id = null;
        if (exception instanceof IHarnessException) {
            id = ((IHarnessException) exception).getErrorId();
        }
        String message = exception.getMessage();
        if (message == null) {
            message = "No error message";
        }
        FailureDescription failure =
                CurrentInvocation.createFailure(message, id)
                        .setCause(exception)
                        .setFailureStatus(defaultStatus);
        if (id != null) {
            failure.setErrorIdentifier(id);
            failure.setFailureStatus(id.status());
        }
        return failure;
    }

    private void reportHostLog(ITestInvocationListener listener, IConfiguration config) {
        String name = TRADEFED_LOG_NAME;
        if (mDelegatedInvocation) {
            name = TRADEFED_DELEGATED_LOG_NAME;
        }
        reportHostLog(listener, config, name);
    }

    private void reportHostLog(
            ITestInvocationListener listener, IConfiguration config, String name) {
        ILeveledLogOutput logger = config.getLogOutput();
        try (InputStreamSource globalLogSource = logger.getLog()) {
            if (globalLogSource != null) {
                if (config.getCommandOptions().getHostLogSuffix() != null) {
                    name += config.getCommandOptions().getHostLogSuffix();
                }
                listener.testLog(name, LogDataType.HOST_LOG, globalLogSource);
            } else {
                // Only print the non-logging if we are not a stdout logger
                if (!(logger instanceof StdoutLogger)) {
                    CLog.i("Skip logging %s to a file with logger '%s'", name, logger);
                }
            }
        }
        // once tradefed log is reported, all further log calls for this invocation can get lost
        // unregister logger so future log calls get directed to the tradefed global log
        getLogRegistry().unregisterLogger();
        logger.closeLog();
    }

    private void takeBugreport(
            ITestDevice device, ITestInvocationListener listener, String bugreportName) {
        if (device == null) {
            return;
        }
        if (device.getIDevice() instanceof StubDevice) {
            return;
        }
        if (!TestDeviceState.ONLINE.equals(device.getDeviceState())) {
            CLog.d("Skipping bugreportz on %s. Device is offline.");
            return;
        }
        // logBugreport will report a regular bugreport if bugreportz is not supported.
        try {
            boolean res =
                    device.logBugreport(
                            String.format("%s_%s", bugreportName, device.getSerialNumber()),
                            listener);
            if (!res) {
                CLog.w("Error when collecting bugreport for device '%s'", device.getSerialNumber());
            }
        } catch (RuntimeException e) {
            CLog.e("Harness Exception while collecting bugreport");
            CLog.e(e);
        }
    }

    /**
     * Gets the {@link ILogRegistry} to use.
     * <p/>
     * Exposed for unit testing.
     */
    ILogRegistry getLogRegistry() {
        return LogRegistry.getLogRegistry();
    }

    /**
     * Utility method to fetch the default {@link IRunUtil} singleton
     * <p />
     * Exposed for unit testing.
     */
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    @Override
    public String toString() {
        return mStatus;
    }

    /**
     * Log the battery level of each device in the invocation.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param event a {@link String} describing the context of the logging (initial, setup, etc.).
     */
    @VisibleForTesting
    void logDeviceBatteryLevel(IInvocationContext context, String event) {
        for (ITestDevice testDevice : context.getDevices()) {
            if (testDevice == null) {
                continue;
            }
            if (testDevice.getIDevice() instanceof StubDevice) {
                continue;
            }
            if (testDevice instanceof RemoteAndroidVirtualDevice
                    || testDevice instanceof NestedRemoteDevice) {
                // Vritual devices have a fake battery there is no point in logging it.
                continue;
            }
            Integer batteryLevel = testDevice.getBattery();
            if (batteryLevel == null) {
                CLog.v("Failed to get battery level for %s", testDevice.getSerialNumber());
                continue;
            }
            CLog.v("%s - %s - %d%%", BATT_TAG, event, batteryLevel);
            context.getBuildInfo(testDevice)
                    .addBuildAttribute(
                            String.format(
                                    BATTERY_ATTRIBUTE_FORMAT_KEY,
                                    testDevice.getSerialNumber(),
                                    event),
                            batteryLevel.toString());
        }
    }

    /**
     * Log the invocation configuration as one large XML detailing all settings in use.
     *
     * @param config the {@link IConfiguration} of this test run
     * @param listener the {@link ITestLogger} with which to register the log
     */
    private void logExpandedConfiguration(IConfiguration config, ITestLogger listener) {
        try (StringWriter configXmlWriter = new StringWriter();
                PrintWriter wrapperWriter = new PrintWriter(configXmlWriter)) {
            config.dumpXml(wrapperWriter);
            wrapperWriter.flush();
            // Specified UTF-8 encoding for an abundance of caution, but its possible we could want
            // something else in the future
            byte[] configXmlByteArray = configXmlWriter.toString().getBytes("UTF-8");
            try (InputStreamSource source = new ByteArrayInputStreamSource(configXmlByteArray)) {
                String configOutputName;
                boolean isSandboxParent = config.getCommandOptions().shouldUseSandboxing();
                boolean isSandboxChild = config.getConfigurationDescription().shouldUseSandbox();
                if (isSandboxParent || isSandboxChild) {
                    // Either the parent or child of a sandbox so we need to tailor the config
                    // logging names
                    String prefix;
                    if (isSandboxChild) {
                        prefix = "child-sandbox";
                    } else {
                        prefix = "parent-sandbox";
                    }

                    configOutputName = String.format("%s-%s", prefix, TRADEFED_CONFIG_NAME);
                } else {
                    // No sandboxing involved (at least known), so use the default name
                    configOutputName = TRADEFED_CONFIG_NAME;
                }

                listener.testLog(configOutputName, LogDataType.HARNESS_CONFIG, source);
            }
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /**
     * Invoke {@link IInvocationExecution#fetchBuild(TestInformation, IConfiguration, IRescheduler,
     * ITestInvocationListener)} and handles the output as well as failures.
     *
     * @param testInfo the {@link TestInformation} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param rescheduler the {@link IRescheduler}, for rescheduling portions of the invocation for
     *     execution on another resource(s)
     * @param listener the {@link ITestInvocation} to report build download failures.
     * @param invocationPath the {@link IInvocationExecution} driving the invocation.
     * @return True if we successfully downloaded the build, false otherwise.
     */
    private boolean invokeFetchBuild(
            TestInformation testInfo,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener,
            IInvocationExecution invocationPath) {
        CurrentInvocation.setActionInProgress(ActionInProgress.FETCHING_ARTIFACTS);
        Exception buildException = null;
        boolean res = false;
        try {
            res = invocationPath.fetchBuild(testInfo, config, rescheduler, listener);
            if (res) {
                // Successful fetch of build.
                CurrentInvocation.setActionInProgress(ActionInProgress.UNSET);
                return true;
            }
            // In case of build not found issues.
            mStatus = "(no build to test)";
            // Set the exit code to error
            buildException =
                    new BuildRetrievalError(
                            "No build found to test.", InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
        } catch (BuildRetrievalError | RuntimeException | DeviceNotAvailableException e) {
            buildException = e;
        }
        setExitCode(ExitCode.NO_BUILD, buildException);
        // Report an empty invocation, so this error is sent to listeners
        startInvocation(config, testInfo.getContext(), listener);
        reportFailure(
                createFailureFromException(buildException, FailureStatus.INFRA_FAILURE), listener);
        for (ITestDevice device : testInfo.getContext().getDevices()) {
            invocationPath.reportLogs(device, listener, Stage.ERROR);
        }
        reportHostLog(listener, config);
        reportInvocationEnded(config, testInfo.getContext(), listener, 0L);
        return false;
    }

    /**
     * Invoke {@link IConfiguration#resolveDynamicOptions(DynamicRemoteFileResolver)} to resolve the
     * dynamic files.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param listener the {@link ITestInvocation} to report build download failures.
     * @param invocationPath the {@link IInvocationExecution} driving the invocation.
     * @param mode The current {@link RunMode} of the invocation.
     * @return True if we successfully downloaded the build, false otherwise.
     */
    private boolean invokeRemoteDynamic(
            IInvocationContext context,
            IConfiguration config,
            ITestInvocationListener listener,
            IInvocationExecution invocationPath,
            RunMode mode) {
        try {
            // Don't resolve for remote invocation, wait until we are inside the remote.
            if (RunMode.REMOTE_INVOCATION.equals(mode)) {
                return true;
            }
            CurrentInvocation.setActionInProgress(ActionInProgress.FETCHING_ARTIFACTS);
            DynamicRemoteFileResolver resolver = new DynamicRemoteFileResolver();
            resolver.setDevice(context.getDevices().get(0));
            resolver.addExtraArgs(config.getCommandOptions().getDynamicDownloadArgs());
            config.resolveDynamicOptions(resolver);
            CurrentInvocation.setActionInProgress(ActionInProgress.UNSET);
            return true;
        } catch (RuntimeException | BuildRetrievalError | ConfigurationException e) {
            // We don't have a reporting buildInfo at this point
            IBuildInfo info = backFillBuildInfoForReporting(config.getCommandLine());

            // In case of build not found issues.
            mStatus = "(failed dynamic download)";
            // Set the exit code to error
            setExitCode(ExitCode.NO_BUILD, e);
            context.addDeviceBuildInfo(context.getDeviceConfigNames().get(0), info);

            // Report an empty invocation, so this error is sent to listeners
            startInvocation(config, context, listener);
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
            for (ITestDevice device : context.getDevices()) {
                invocationPath.reportLogs(device, listener, Stage.ERROR);
            }
            reportHostLog(listener, config);
            reportInvocationEnded(config, context, listener, 0L);
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void invoke(
            IInvocationContext context,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener... extraListeners)
            throws DeviceNotAvailableException, Throwable {
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.INVOCATION_START, System.currentTimeMillis());
        // Handle the automated reporting
        applyAutomatedReporters(config);

        for (ITestInvocationListener listener : extraListeners) {
            if (listener instanceof IScheduledInvocationListener) {
                mSchedulerListeners.add((IScheduledInvocationListener) listener);
            }
        }
        // Create the TestInformation for the invocation
        // TODO: Use invocation-id in the workfolder name
        Object sharedInfoObject =
                config.getConfigurationObject(ShardHelper.SHARED_TEST_INFORMATION);
        TestInformation sharedTestInfo = null;
        TestInformation info = null;
        if (sharedInfoObject != null) {
            sharedTestInfo = (TestInformation) sharedInfoObject;
            // During sharding we share everything except the invocation context
            info = TestInformation.createModuleTestInfo(sharedTestInfo, context);
        }
        if (info == null) {
            File mWorkFolder = FileUtil.createTempDir("tf-workfolder");
            info =
                    TestInformation.newBuilder()
                            .setInvocationContext(context)
                            .setDependenciesFolder(mWorkFolder)
                            .build();
        }
        CurrentInvocation.addInvocationInfo(InvocationInfo.WORK_FOLDER, info.dependenciesFolder());

        CleanUpInvocationFiles cleanUpThread = new CleanUpInvocationFiles(info, config);
        Runtime.getRuntime().addShutdownHook(cleanUpThread);
        registerExecutionFiles(info.executionFiles());

        List<ITestInvocationListener> allListeners =
                new ArrayList<>(config.getTestInvocationListeners().size() + extraListeners.length);
        // If it's not a subprocess, report the passed tests.
        ReportPassedTests reportPass = null;
        if (config.getCommandOptions().reportPassedTests()
                && !config.getCommandOptions()
                        .getInvocationData()
                        .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME)) {
            reportPass = new ReportPassedTests();
            allListeners.add(reportPass);
        }
        allListeners.addAll(config.getTestInvocationListeners());
        allListeners.addAll(Arrays.asList(extraListeners));
        allListeners.add(mUnavailableMonitor);
        ITestInvocationListener listener = null;

        // Auto retry feature
        IRetryDecision decision = config.getRetryDecision();
        ResultAggregator aggregator = null;
        decision.setInvocationContext(context);
        // We don't need the aggregator in the subprocess because the parent will take care of it.
        if (!config.getCommandOptions()
                        .getInvocationData()
                        .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME)
                && decision.isAutoRetryEnabled()
                && decision.getMaxRetryCount() > 1
                && !RetryStrategy.NO_RETRY.equals(decision.getRetryStrategy())) {
            CLog.d("Auto-retry enabled, using the ResultAggregator to handle multiple retries.");
            aggregator = new ResultAggregator(allListeners, decision.getRetryStrategy());
            allListeners = Arrays.asList(aggregator);
        }

        if (!config.getPostProcessors().isEmpty()) {
            ITestInvocationListener forwarder = new ResultAndLogForwarder(allListeners);
            // Post-processors are the first layer around the final reporters.
            for (IPostProcessor postProcessor : config.getPostProcessors()) {
                if (postProcessor.isDisabled()) {
                    CLog.d("%s has been disabled. skipping.", postProcessor);
                } else {
                    forwarder = postProcessor.init(forwarder);
                }
            }
            listener = new LogSaverResultForwarder(config.getLogSaver(), Arrays.asList(forwarder));
        } else {
            listener = new LogSaverResultForwarder(config.getLogSaver(), allListeners);
        }
        if (reportPass != null) {
            reportPass.setLogger(listener);
        }

        RunMode mode = RunMode.REGULAR;
        if (config.getConfigurationDescription().shouldUseSandbox()) {
            mode = RunMode.SANDBOX;
        }
        if (config.getCommandOptions().shouldUseSandboxing()) {
            mode = RunMode.PARENT_SANDBOX;
        }
        if (context.getDevices().get(0) instanceof ManagedRemoteDevice) {
            mode = RunMode.REMOTE_INVOCATION;
        }
        if (config.getConfigurationObject(TradefedDelegator.DELEGATE_OBJECT) != null) {
            mDelegatedInvocation = true;
            mode = RunMode.DELEGATED_INVOCATION;
        }
        IInvocationExecution invocationPath = createInvocationExec(mode);
        updateInvocationContext(context, config);

        // Create the Guice scope
        InvocationScope scope = getInvocationScope();
        scope.enter();
        // Seed our TF objects to the Guice scope
        scope.seed(IRescheduler.class, rescheduler);
        scope.seedConfiguration(config);
        boolean sharding = false;
        try {
            ILeveledLogOutput leveledLogOutput = config.getLogOutput();
            leveledLogOutput.init();
            if (leveledLogOutput instanceof BaseLeveledLogOutput) {
                ((BaseLeveledLogOutput) leveledLogOutput).initFilters(config);
            }
            getLogRegistry().registerLogger(leveledLogOutput);
            mStatus = "resolving dynamic options";
            boolean resolverSuccess =
                    invokeRemoteDynamic(context, config, listener, invocationPath, mode);
            if (!resolverSuccess) {
                return;
            }

            mStatus = "fetching build";
            for (String deviceName : context.getDeviceConfigNames()) {
                context.getDevice(deviceName).clearLastConnectedWifiNetwork();
                context.getDevice(deviceName)
                        .setOptions(config.getDeviceConfigByName(deviceName).getDeviceOptions());
                if (config.getDeviceConfigByName(deviceName)
                        .getDeviceOptions()
                        .isLogcatCaptureEnabled()) {
                    if (!(context.getDevice(deviceName).getIDevice() instanceof StubDevice)) {
                        context.getDevice(deviceName).startLogcat();
                    }
                }
            }

            String cmdLineArgs = config.getCommandLine();
            if (cmdLineArgs != null) {
                CLog.i("Invocation was started with cmd: %s", cmdLineArgs);
            }

            long start = System.currentTimeMillis();
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.FETCH_BUILD_START, start);
            boolean providerSuccess =
                    invokeFetchBuild(info, config, rescheduler, listener, invocationPath);
            long end = System.currentTimeMillis();
            InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.FETCH_BUILD_END, end);
            long fetchBuildDuration = end - start;
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.FETCH_BUILD, fetchBuildDuration);
            CLog.d("Fetch build duration: %s", TimeUtil.formatElapsedTime(fetchBuildDuration));
            if (!providerSuccess) {
                return;
            }

            boolean deviceInit = false;
            // If the top level invocation has --use-sandbox do not shard there. It will shard in
            // the child invocation.
            if (RunMode.REGULAR.equals(mode) || RunMode.SANDBOX.equals(mode)) {
                mStatus = "sharding";

                // TODO: Handle local sharding and special devices
                Integer shardCount = config.getCommandOptions().getShardCount();
                Integer shardIndex = config.getCommandOptions().getShardIndex();
                // Special Handling in case of sharding within the same invocation (in-place): Some
                // devices (Remote devices for example) require extra preparation step to be
                // available, but sharding requires the device to be available in some cases. So
                // we call the device setup early to meet all the requirements.
                boolean startInvocationCalled = false;
                if (shardCount != null && shardIndex != null) {
                    deviceInit = true;
                    startInvocation(config, context, listener);
                    startInvocationCalled = true;
                    try {
                        invocationPath.runDevicePreInvocationSetup(context, config, listener);
                    } catch (DeviceNotAvailableException | TargetSetupError e) {
                        CLog.e(e);
                        FailureDescription failure = FailureDescription.create(e.getMessage());
                        failure.setCause(e).setFailureStatus(FailureStatus.INFRA_FAILURE);
                        if (e instanceof DeviceNotAvailableException) {
                            setExitCode(ExitCode.DEVICE_UNAVAILABLE, e);
                        } else {
                            setExitCode(ExitCode.THROWABLE_EXCEPTION, e);
                        }
                        try {
                            invocationPath.runDevicePostInvocationTearDown(context, config, e);
                        } finally {
                            reportFailure(
                                    createFailureFromException(e, FailureStatus.INFRA_FAILURE),
                                    listener);
                            // Reports the logs
                            for (ITestDevice device : context.getDevices()) {
                                invocationPath.reportLogs(device, listener, Stage.ERROR);
                            }
                            reportHostLog(listener, config);
                            reportInvocationEnded(config, context, listener, 0L);
                        }
                        return;
                    }
                }

                try {
                    sharding = invocationPath.shardConfig(config, info, rescheduler, listener);
                } catch (RuntimeException unexpected) {
                    CLog.e("Exception during sharding.");
                    CLog.e(unexpected);
                    if (deviceInit) {
                        // If we did an early setup, do the tear down.
                        invocationPath.runDevicePostInvocationTearDown(context, config, unexpected);
                    }
                    // Call the reporting to get debugging infos.
                    if (!startInvocationCalled) {
                        startInvocation(config, context, listener);
                    }
                    reportFailure(
                            createFailureFromException(unexpected, FailureStatus.INFRA_FAILURE)
                                    .setActionInProgress(ActionInProgress.TEST),
                            listener);
                    reportHostLog(listener, config);
                    listener.invocationEnded(0L);
                    return;
                }
                if (sharding) {
                    CLog.i(
                            "Invocation for %s has been sharded, rescheduling",
                            context.getSerials());
                    // Log the chunk of parent host_log before sharding
                    reportHostLog(listener, config, TRADEFED_LOG_NAME + BEFORE_SHARDING_SUFFIX);
                    config.getLogSaver().invocationEnded(0L);
                    if (aggregator != null) {
                        // The host_log is not available yet to reporters that don't support
                        // granular results, so forward it.
                        aggregator.forwardAggregatedInvocationLogs();
                        aggregator.cleanEventsFiles();
                    }
                    return;
                }
            }
            // Once we have all the information we can start the invocation.
            if (!deviceInit) {
                startInvocation(config, context, listener);
            }
            if (!RunMode.DELEGATED_INVOCATION.equals(mode)
                    && (config.getTests() == null || config.getTests().isEmpty())) {
                CLog.e("No tests to run");
                if (deviceInit) {
                    // If we did an early setup, do the tear down.
                    invocationPath.runDevicePostInvocationTearDown(context, config, null);
                }
                listener.invocationEnded(0L);
                return;
            }

            performInvocation(config, info, invocationPath, listener, deviceInit);
            setExitCode(ExitCode.NO_ERROR, null);
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            scope.exit();
            // Ensure build infos are always cleaned up at the end of invocation.
            invocationPath.cleanUpBuilds(context, config);
            // ensure we always deregister the logger
            for (String deviceName : context.getDeviceConfigNames()) {
                if (!(context.getDevice(deviceName).getIDevice() instanceof StubDevice)) {
                    context.getDevice(deviceName).stopLogcat();
                }
            }
            if (!sharding) {
                // If we are the parent shard, we do not delete the test information
                deleteInvocationFiles(info, config);
            }
            // save remaining logs contents to global log
            getLogRegistry().dumpToGlobalLog(config.getLogOutput());
            // Ensure log is unregistered and closed
            getLogRegistry().unregisterLogger();
            config.getLogOutput().closeLog();
            config.cleanConfigurationData();

            Runtime.getRuntime().removeShutdownHook(cleanUpThread);
        }
    }

    /** Returns the current {@link InvocationScope}. */
    @VisibleForTesting
    InvocationScope getInvocationScope() {
        return InvocationScope.getDefault();
    }

    @VisibleForTesting
    public void registerExecutionFiles(ExecutionFiles executionFiles) {
        CurrentInvocation.registerExecutionFiles(executionFiles);
    }

    /**
     * Helper to set the exit code. Exposed for testing.
     */
    protected void setExitCode(ExitCode code, Throwable stack) {
        mExitCode = code;
        mExitStack = stack;
    }

    protected void addInvocationMetric(InvocationMetricKey key, long value) {
        InvocationMetricLogger.addInvocationMetrics(key, value);
    }

    protected void addInvocationMetric(InvocationMetricKey key, String value) {
        InvocationMetricLogger.addInvocationMetrics(key, value);
    }

    public static String getDeviceLogName(Stage stage) {
        return DEVICE_LOG_NAME_PREFIX + stage.getName();
    }

    public static String getEmulatorLogName(Stage stage) {
        return EMULATOR_LOG_NAME_PREFIX + stage.getName();
    }

    @Override
    public void notifyInvocationStopped(String message) {
        mStopCause = message;
        if (mStopRequestTime == null) {
            mStopRequestTime = System.currentTimeMillis();
        }
    }

    /**
     * Create the invocation path that should be followed.
     *
     * @param mode The mode we are currently running as.
     * @return The {@link IInvocationExecution} describing the invocation.
     */
    public IInvocationExecution createInvocationExec(RunMode mode) {
        switch (mode) {
            case PARENT_SANDBOX:
                return new ParentSandboxInvocationExecution();
            case SANDBOX:
                return new SandboxedInvocationExecution();
            case REMOTE_INVOCATION:
                return new RemoteInvocationExecution();
            case DELEGATED_INVOCATION:
                return new DelegatedInvocationExecution();
            default:
                return new InvocationExecution();
        }
    }

    /** Prints a delimiter for a given Stage of the invocation. */
    public static void printStageDelimiter(Stage phase, boolean end) {
        String startEnd = end ? "ENDING" : "STARTING";
        String message = String.format("===== %s PHASE %s =====", phase, startEnd);
        PrettyPrintDelimiter.printStageDelimiter(message);
    }

    @VisibleForTesting
    protected void applyAutomatedReporters(IConfiguration config) {
        AutomatedReporters autoReport = new AutomatedReporters();
        autoReport.applyAutomatedReporters(config);
    }

    private void logExecuteShellCommand(List<ITestDevice> devices, ITestLogger logger) {
        for (ITestDevice device : devices) {
            if (!(device instanceof NativeDevice)) {
                return;
            }
            File log = ((NativeDevice) device).getExecuteShellCommandLog();
            if (log == null || !log.exists()) {
                return;
            }
            if (log.length() == 0) {
                CLog.d("executeShellCommandLog file was empty, skip logging.");
                return;
            }
            try (InputStreamSource source = new FileInputStreamSource(log)) {
                logger.testLog(
                        String.format("executeShellCommandLog_%s", device.getSerialNumber()),
                        LogDataType.TEXT,
                        source);
            }
        }
    }

    /**
     * Update the {@link IInvocationContext} with additional info from the {@link IConfiguration}.
     *
     * @param context the {@link IInvocationContext}
     * @param config the {@link IConfiguration}
     */
    private void updateInvocationContext(IInvocationContext context, IConfiguration config) {
        context.setTestTag(getTestTag(config));
        if (config.getCommandOptions().getInvocationData().containsKey("subprocess")) {
            // Avoid relogging the properties in a subprocess
            return;
        }
        if (config.getCommandLine() != null) {
            context.addInvocationAttribute(
                    TestInvocation.COMMAND_ARGS_KEY, config.getCommandLine());
        }
        if (config.getCommandOptions().getShardCount() != null) {
            context.addInvocationAttribute(
                    "shard_count", config.getCommandOptions().getShardCount().toString());
        }
        if (config.getCommandOptions().getShardIndex() != null) {
            context.addInvocationAttribute(
                    "shard_index", config.getCommandOptions().getShardIndex().toString());
        }
    }

    /** Helper to create the test tag from the configuration. */
    private String getTestTag(IConfiguration config) {
        String testTag = config.getCommandOptions().getTestTag();
        if (config.getCommandOptions().getTestTagSuffix() != null) {
            testTag =
                    String.format("%s-%s", testTag, config.getCommandOptions().getTestTagSuffix());
        }
        return testTag;
    }

    /**
     * Delete the invocation files if this is the last shard for local sharding or if we are not in
     * a local sharding situation.
     */
    private void deleteInvocationFiles(TestInformation testInfo, IConfiguration config) {
        Object obj = config.getConfigurationObject(ShardHelper.LAST_SHARD_DETECTOR);
        if (obj != null) {
            LastShardDetector lastShardDetector = (LastShardDetector) obj;
            if (!lastShardDetector.isLastShardDone()) {
                return;
            }
        }
        // Delete the invocation work directory at the end
        FileUtil.recursiveDelete(testInfo.dependenciesFolder());
        // Delete all the execution files
        testInfo.executionFiles().clearFiles();
    }

    private Map<ITestDevice, FreeDeviceState> handleAndLogReleaseState(
            IInvocationContext context, Throwable exception, Throwable tearDownException) {
        if (exception == null && tearDownException != null) {
            exception = tearDownException;
        } else if (tearDownException instanceof DeviceNotAvailableException) {
            // Use what we consider a later & higher priority error
            exception = tearDownException;
        }
        // Capture the FreeDeviceState of the primary device
        Map<ITestDevice, FreeDeviceState> devicesStates =
                CommandScheduler.createReleaseMap(context, exception);
        if (devicesStates.size() >= 1) {
            addInvocationMetric(
                    InvocationMetricKey.DEVICE_RELEASE_STATE,
                    devicesStates.values().iterator().next().toString());
        }
        int countPhysicalLost = 0;
        int countVirtualLost = 0;
        for (Entry<ITestDevice, FreeDeviceState> fds : devicesStates.entrySet()) {
            // TODO: Rely on the FailureStatus for lost devices instead
            if ((fds.getKey().getIDevice() instanceof TcpDevice)
                    && exception instanceof DeviceNotAvailableException) {
                countVirtualLost++;
                continue;
            }
            if (fds.getKey().getIDevice() instanceof StubDevice) {
                continue;
            }
            if (FreeDeviceState.UNAVAILABLE.equals(fds.getValue())
                    || FreeDeviceState.UNRESPONSIVE.equals(fds.getValue())) {
                // Remote devices are not seen as stub, but are still virtual devices
                if (fds.getKey() instanceof RemoteAndroidDevice
                        || fds.getKey() instanceof NestedRemoteDevice) {
                    countVirtualLost++;
                } else {
                    countPhysicalLost++;
                }
            }
        }
        if (countPhysicalLost > 0) {
            addInvocationMetric(InvocationMetricKey.DEVICE_LOST_DETECTED, countPhysicalLost);
            if (GlobalConfiguration.getDeviceManagerInstance() instanceof DeviceManager) {
                String adbOutput =
                        ((DeviceManager) GlobalConfiguration.getDeviceManagerInstance())
                                .executeGlobalAdbCommand("devices");
                CLog.e("'adb devices' output:\n%s", adbOutput);
            }
        } else if (countVirtualLost > 0) {
            CLog.e("Counting as virtual_device_lost.");
            addInvocationMetric(InvocationMetricKey.VIRTUAL_DEVICE_LOST_DETECTED, countVirtualLost);
        }
        return devicesStates;
    }

    private void reportRecoveryLogs(List<ITestDevice> devices, ITestInvocationListener listener) {
        for (ITestDevice device : devices) {
            if (device == null) {
                continue;
            }
            if (device.getIDevice() instanceof StubDevice) {
                continue;
            }
            if (device.getDeviceState() != TestDeviceState.RECOVERY) {
                continue;
            }
            try {
                File recovery_log = device.pullFile(RECOVERY_LOG_DEVICE_PATH);
                if (recovery_log == null) {
                    return;
                }
                try (FileInputStreamSource fis = new FileInputStreamSource(recovery_log)) {
                    listener.testLog(
                            String.format("recovery_log_%s.txt", device.getSerialNumber()),
                            LogDataType.TEXT,
                            fis);
                }
            } catch (DeviceNotAvailableException e) {
                CLog.i("Device unavailable, can't pull recovery.log");
            }
        }
    }

    private void reportInvocationEnded(
            IConfiguration config,
            IInvocationContext context,
            ITestInvocationListener listener,
            long elapsedTime) {
        // Init a log for the end of the host_log.
        ILeveledLogOutput endHostLog = config.getLogOutput();
        try {
            endHostLog.init();
            getLogRegistry().registerLogger(endHostLog);
        } catch (IOException e) {
            CLog.e(e);
            endHostLog = null;
        }

        PrettyPrintDelimiter.printStageDelimiter("===== Result Reporters =====");
        try {
            // Copy the invocation metrics to the context
            ((InvocationContext) context).logInvocationMetrics();
            listener.invocationEnded(elapsedTime);
        } finally {
            InvocationMetricLogger.clearInvocationMetrics();
            if (endHostLog != null) {
                endHostLog.closeLog();
                getLogRegistry().unregisterLogger();
            }
        }
    }

    /**
     * If no previous exception occurred, report if the device is not available anymore after tests
     * finish running.
     */
    private void checkDevicesAvailable(
            List<ITestDevice> devices, ITestInvocationListener listener) {
        for (ITestDevice device : devices) {
            if (device == null) {
                continue;
            }
            if (device.getIDevice() instanceof StubDevice) {
                continue;
            }
            try {
                device.waitForDeviceOnline();
            } catch (DeviceNotAvailableException e) {
                String msg =
                        String.format("Device was left offline after tests: %s", e.getMessage());
                DeviceNotAvailableException wrap =
                        new DeviceNotAvailableException(msg, e, e.getSerial(), e.getErrorId());
                reportFailure(
                        createFailureFromException(wrap, FailureStatus.INFRA_FAILURE), listener);
            }
        }
    }

    /**
     * Helper that use the command line to backfill a {@link IBuildInfo} for reporting in case of
     * download failure.
     */
    public static IBuildInfo backFillBuildInfoForReporting(String commandLine) {
        IBuildInfo info = new BuildInfo();
        CommandLineBuildInfoBuilder builder = new CommandLineBuildInfoBuilder();
        try {
            List<String> command =
                    new ArrayList<>(
                            Arrays.asList(
                                    QuotationAwareTokenizer.tokenizeLine(commandLine, false)));
            command.remove(0);
            ArgsOptionParser parser = new ArgsOptionParser(builder);
            parser.parseBestEffort(command, true);
            info = builder.createBuild();
        } catch (ConfigurationException ignore) {
            CLog.e(ignore);
        }
        return info;
    }

    /** Helper Thread that ensures host_log is reported in case of killed JVM */
    private class ReportHostLog extends Thread {

        private ITestInvocationListener mListener;
        private IConfiguration mConfiguration;

        public ReportHostLog(ITestInvocationListener listener, IConfiguration config) {
            mListener = listener;
            mConfiguration = config;
        }

        @Override
        public void run() {
            // Report all the logs that always be reported anyway.
            reportHostLog(mListener, mConfiguration);
        }
    }

    /** Helper Thread to ensure invocation files are deleted in case of killed JVM */
    private class CleanUpInvocationFiles extends Thread {

        private TestInformation mTestInfo;
        private IConfiguration mConfig;

        public CleanUpInvocationFiles(TestInformation currentInfo, IConfiguration config) {
            mTestInfo = currentInfo;
            mConfig = config;
        }

        @Override
        public void run() {
            deleteInvocationFiles(mTestInfo, mConfig);
        }
    }

    /** Measure the size of the work folder. */
    private Long measureWorkFolderSize(IConfiguration config, TestInformation testInfo) {
        if (testInfo == null) {
            return null;
        }
        File workFolder = testInfo.dependenciesFolder();
        CLog.d("Measuring size of %s", workFolder);
        if (workFolder == null || !workFolder.exists()) {
            return null;
        }
        // Only measure in parent process
        if (config.getCommandOptions()
                .getInvocationData()
                .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME)) {
            CLog.d("Skip measuring size since we are in subprocess");
            return null;
        }

        Object obj = config.getConfigurationObject(ShardHelper.LAST_SHARD_DETECTOR);
        if (obj != null) {
            LastShardDetector lastShardDetector = (LastShardDetector) obj;
            if (!lastShardDetector.isLastShardDone()) {
                return null;
            }
        }
        return FileUtil.sizeOfDirectory(workFolder);
    }

    @Override
    public ExitInformation getExitInfo() {
        ExitInformation info = new ExitInformation();
        info.mExitCode = this.mExitCode;
        info.mStack = this.mExitStack;
        return info;
    }
}
