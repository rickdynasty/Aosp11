/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.compatibility.common.tradefed.command;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.compatibility.common.tradefed.build.CompatibilityBuildProvider;
import com.android.compatibility.common.tradefed.result.SubPlanHelper;
import com.android.compatibility.common.tradefed.result.suite.CertificationResultXml;
import com.android.compatibility.common.tradefed.testtype.suite.CompatibilityTestSuite;
import com.android.compatibility.common.util.ResultHandler;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.Console;
import com.android.tradefed.config.ArgsOptionParser;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.suite.SuiteResultHolder;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IRuntimeHintProvider;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.testtype.suite.SuiteModuleLoader;
import com.android.tradefed.testtype.suite.TestSuiteInfo;
import com.android.tradefed.testtype.suite.params.ModuleParameters;
import com.android.tradefed.util.AbiUtils;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.Pair;
import com.android.tradefed.util.RegexTrie;
import com.android.tradefed.util.TableFormatter;
import com.android.tradefed.util.TimeUtil;
import com.android.tradefed.util.VersionParser;

import com.google.common.base.Joiner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An extension of Tradefed's console which adds features specific to compatibility testing.
 */
public class CompatibilityConsole extends Console {

    /**
     * Hard coded list of modules to be excluded from manual module sharding
     * @see #splitModules(int)
     */
    private final static Set<String> MODULE_SPLIT_EXCLUSIONS = new HashSet<>();
    static {
        MODULE_SPLIT_EXCLUSIONS.add("CtsDeqpTestCases");
    }
    private final static String ADD_PATTERN = "a(?:dd)?";
    private static final String LATEST_RESULT_DIR = "latest";
    private CompatibilityBuildHelper mBuildHelper;
    private IBuildInfo mBuildInfo;

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        String buildNumber = TestSuiteInfo.getInstance().getBuildNumber();
        String versionFile = VersionParser.fetchVersion();
        if (versionFile != null) {
            buildNumber = versionFile;
        }
        printLine(
                String.format(
                        "Android %s %s (%s)",
                        TestSuiteInfo.getInstance().getFullName(),
                        TestSuiteInfo.getInstance().getVersion(),
                        buildNumber));
        printLine("Use \"help\" or \"help all\" to get more information on running commands.");
        super.run();
    }

    /**
     * Adds the 'list plans', 'list modules' and 'list results' commands
     */
    @Override
    protected void setCustomCommands(RegexTrie<Runnable> trie, List<String> genericHelp,
            Map<String, String> commandHelp) {

        genericHelp.add("Enter 'help add'        for help with 'add subplan' commands");
        genericHelp.add("\t----- " + TestSuiteInfo.getInstance().getFullName() + " usage ----- ");
        genericHelp.add("Usage: run <plan> [--module <module name>] [ options ]");
        genericHelp.add("Example: run cts --module CtsGestureTestCases --bugreport-on-failure");
        genericHelp.add("");

        trie.put(new Runnable() {
            @Override
            public void run() {
                listPlans();
            }
        }, LIST_PATTERN, "p(?:lans)?");
        trie.put(
                new Runnable() {
                    @Override
                    public void run() {
                        listModules(null);
                    }
                },
                LIST_PATTERN,
                "m(?:odules)?");
        trie.put(
                new ArgRunnable<CaptureList>() {
                    @Override
                    public void run(CaptureList args) {
                        String parameter = args.get(2).get(0);
                        listModules(parameter);
                    }
                },
                LIST_PATTERN,
                "m(?:odules)?",
                "(.*)");
        trie.put(new Runnable() {
            @Override
            public void run() {
                listResults();
            }
        }, LIST_PATTERN, "r(?:esults)?");
        trie.put(new Runnable() {
            @Override
            public void run() {
                listSubPlans();
            }
        }, LIST_PATTERN, "s(?:ubplans)?");
        trie.put(new ArgRunnable<CaptureList>() {
            @Override
            public void run(CaptureList args) {
                // Skip 2 tokens to get past split and modules pattern
                String arg = args.get(2).get(0);
                int shards = Integer.parseInt(arg);
                if (shards <= 1) {
                    printLine("number of shards should be more than 1");
                    return;
                }
                splitModules(shards);
            }
        }, "split", "m(?:odules)?", "(\\d+)");
        trie.put(new ArgRunnable<CaptureList>() {
            @Override
            public void run(CaptureList args) {
                // Skip 2 tokens to get past "add" and "subplan"
                String[] flatArgs = new String[args.size() - 2];
                for (int i = 2; i < args.size(); i++) {
                    flatArgs[i - 2] = args.get(i).get(0);
                }
                addSubPlan(flatArgs);
            }
        }, ADD_PATTERN, "s(?:ubplan)?", null);
        trie.put(new ArgRunnable<CaptureList>() {
            @Override
            public void run(CaptureList args) {
                printLine("'add subplan' requires parameters, use 'help add' to get more details");
            }
        }, ADD_PATTERN, "s(?:ubplan)?");
        trie.put(new Runnable() {
            @Override
            public void run() {
                printLine(String.format("Android %s %s (%s)",
                        TestSuiteInfo.getInstance().getFullName(),
                        TestSuiteInfo.getInstance().getVersion(),
                        TestSuiteInfo.getInstance().getBuildNumber()));
            }
        }, "version"); // override tradefed 'version' command to print test suite name and version

        // find existing help for 'LIST_PATTERN' commands, and append these commands help
        String listHelp = commandHelp.get(LIST_PATTERN);
        if (listHelp == null) {
            // no help? Unexpected, but soldier on
            listHelp = new String();
        }
        String combinedHelp =
                listHelp
                        + LINE_SEPARATOR
                        + "\t----- "
                        + TestSuiteInfo.getInstance().getFullName()
                        + " specific options ----- "
                        + LINE_SEPARATOR
                        + "\tp[lans]               List all plans available"
                        + LINE_SEPARATOR
                        + "\tm[odules]             List all modules available"
                        + LINE_SEPARATOR
                        + String.format(
                                "\tm[odules] [module parameter] List all modules matching the "
                                        + "parameter. (available params: %s)",
                                Arrays.asList(ModuleParameters.values()))
                        + LINE_SEPARATOR
                        + "\tr[esults]             List all results"
                        + LINE_SEPARATOR;
        commandHelp.put(LIST_PATTERN, combinedHelp);

        // Update existing RUN_PATTERN with CTS specific extra run possibilities.
        String runHelp = commandHelp.get(RUN_PATTERN);
        if (runHelp == null) {
            runHelp = new String();
        }
        String combinedRunHelp = runHelp +
                LINE_SEPARATOR +
                "\t----- " + TestSuiteInfo.getInstance().getFullName()
                + " specific options ----- " + LINE_SEPARATOR +
                "\t<plan> --module/-m <module>       Run a test module" + LINE_SEPARATOR +
                "\t<plan> --module/-m <module> --test/-t <test_name>    Run a specific test from" +
                " the module. Test name can be <package>.<class>, <package>.<class>#<method> or "
                + "<native_binary_name>" + LINE_SEPARATOR +
                "\t\tAvailable Options:" + LINE_SEPARATOR +
                "\t\t\t--serial/-s <device_id>: The device to run the test on" + LINE_SEPARATOR +
                "\t\t\t--abi/-a <abi>         : The ABI to run the test against" + LINE_SEPARATOR +
                "\t\t\t--logcat-on-failure    : Capture logcat when a test fails"
                + LINE_SEPARATOR +
                "\t\t\t--bugreport-on-failure : Capture a bugreport when a test fails"
                + LINE_SEPARATOR +
                "\t\t\t--screenshot-on-failure: Capture a screenshot when a test fails"
                + LINE_SEPARATOR +
                "\t\t\t--shard-count <shards>: Shards a run into the given number of independent " +
                "chunks, to run on multiple devices in parallel." + LINE_SEPARATOR +
                "\t ----- In order to retry a previous run -----" + LINE_SEPARATOR +
                "\tretry --retry <session id to retry> [--retry-type <FAILED | NOT_EXECUTED>]"
                + LINE_SEPARATOR +
                "\t\tWithout --retry-type, retry will run both FAIL and NOT_EXECUTED tests"
                + LINE_SEPARATOR;
        commandHelp.put(RUN_PATTERN, combinedRunHelp);

        commandHelp.put(ADD_PATTERN, String.format(
                "%s help:" + LINE_SEPARATOR +
                "\tadd s[ubplan]: create a subplan from a previous session" + LINE_SEPARATOR +
                "\t\tAvailable Options:" + LINE_SEPARATOR +
                "\t\t\t--session <session_id>: The session used to create a subplan"
                + LINE_SEPARATOR +
                "\t\t\t--name/-n <subplan_name>: The name of the new subplan" + LINE_SEPARATOR +
                "\t\t\t--result-type <status>: Which results to include in the subplan. "
                + "One of passed, failed, not_executed. Repeatable" + LINE_SEPARATOR,
                ADD_PATTERN));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getConsolePrompt() {
        return String.format("%s-tf > ", TestSuiteInfo.getInstance().getName().toLowerCase());
    }

    /**
     * List all the modules available in the suite, if a specific parameter is requested, only
     * display that one.
     *
     * @param moduleParameter The parameter requested to be displayed. Null if all should be shown.
     */
    private void listModules(String moduleParameter) {
        CompatibilityTestSuite test = new CompatibilityTestSuite();
        Set<String> abiStrings = ITestSuite.getAbisForBuildTargetArchFromSuite();
        Set<IAbi> abis = new LinkedHashSet<>();
        for (String abi : abiStrings) {
            if (AbiUtils.isAbiSupportedByCompatibility(abi)) {
                abis.add(new Abi(abi, AbiUtils.getBitness(abi)));
            }
        }
        test.setAbis(abis);
        if (getBuild() != null) {
            test.setEnableParameterizedModules(true);
            test.setEnableOptionalParameterizedModules(true);
            if (moduleParameter != null) {
                test.setModuleParameter(ModuleParameters.valueOf(moduleParameter.toUpperCase()));
            }
            test.setBuild(getBuild());
            LinkedHashMap<String, IConfiguration> configs = test.loadTests();
            printLine(String.format("%s", Joiner.on("\n").join(configs.keySet())));
        } else {
            printLine("Error fetching information about modules.");
        }
    }

    private void listPlans() {
        printLine("Available plans include:");
        ConfigurationFactory.getInstance().printHelp(System.out);
    }

    private void splitModules(int shards) {
        File[] files = null;
        try {
            files = getBuildHelper().getTestsDir().listFiles(new SuiteModuleLoader.ConfigFilter());
        } catch (FileNotFoundException e) {
            printLine(e.getMessage());
            e.printStackTrace();
        }
        // parse through all config files to get runtime hints
        if (files != null && files.length > 0) {
            IConfigurationFactory configFactory = ConfigurationFactory.getInstance();
            List<Pair<String, Long>> moduleRuntime = new ArrayList<>();
            // parse through all config files to calculate module execution time
            for (File file : files) {
                IConfiguration config = null;
                String moduleName = file.getName().split("\\.")[0];
                if (MODULE_SPLIT_EXCLUSIONS.contains(moduleName)) {
                    continue;
                }
                try {
                    config = configFactory.createConfigurationFromArgs(new String[]{
                            file.getAbsolutePath(),
                    });
                } catch (ConfigurationException ce) {
                    printLine("Error loading config file: " + file.getAbsolutePath());
                    CLog.e(ce);
                    continue;
                }
                long runtime = 0;
                for (IRemoteTest test : config.getTests()) {
                    if (test instanceof IRuntimeHintProvider) {
                        runtime += ((IRuntimeHintProvider) test).getRuntimeHint();
                    } else {
                        CLog.w("Using default 1m runtime estimation for test type %s",
                                test.getClass().getSimpleName());
                        runtime += 60 * 1000;
                    }
                }
                moduleRuntime.add(new Pair<String, Long>(moduleName, runtime));
            }
            // sort list modules in descending order of runtime hint
            Collections.sort(moduleRuntime, new Comparator<Pair<String, Long>>() {
                @Override
                public int compare(Pair<String, Long> o1, Pair<String, Long> o2) {
                    return o2.second.compareTo(o1.second);
                }
            });
            // partition list of modules based on the runtime hint
            List<List<Pair<String, Long>>> splittedModules = new ArrayList<>();
            for (int i = 0; i < shards; i++) {
                splittedModules.add(new ArrayList<>());
            }
            int shardIndex = 0;
            int increment = 1;
            long[] shardTimes = new long[shards];
            // go through the sorted list, distribute modules into shards in zig-zag pattern to get
            // an even execution time among shards
            for (Pair<String, Long> module : moduleRuntime) {
                splittedModules.get(shardIndex).add(module);
                // also collect total runtime per shard
                shardTimes[shardIndex] += module.second;
                shardIndex += increment;
                // zig-zagging: first distribute modules from shard 0 to N, then N down to 0, repeat
                if (shardIndex == shards) {
                    increment = -1;
                    shardIndex = shards - 1;
                }
                if (shardIndex == -1) {
                    increment = 1;
                    shardIndex = 0;
                }
            }
            shardIndex = 0;
            // print the final shared lists
            for (List<Pair<String, Long>> shardedModules : splittedModules) {
                StringBuilder lineBuffer = new StringBuilder();
                lineBuffer.append(String.format("shard #%d (%s):",
                        shardIndex, TimeUtil.formatElapsedTime(shardTimes[shardIndex])));
                Iterator<Pair<String, Long>> itr = shardedModules.iterator();
                lineBuffer.append(itr.next().first);
                while (itr.hasNext()) {
                    lineBuffer.append(',');
                    lineBuffer.append(itr.next().first);
                }
                shardIndex++;
                printLine(lineBuffer.toString());
            }
        } else {
            printLine("No modules found");
        }
    }

    private void listResults() {
        TableFormatter tableFormatter = new TableFormatter();
        List<List<String>> table = new ArrayList<>();

        List<File> resultDirs = null;
        Map<SuiteResultHolder, File> holders = new LinkedHashMap<>();
        try {
            resultDirs = getResults(getBuildHelper().getResultsDir());
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Error while parsing results directory", e);
        }
        CertificationResultXml xmlParser = new CertificationResultXml();
        for (File resultDir : resultDirs) {
            if (LATEST_RESULT_DIR.equals(resultDir.getName())) {
                continue;
            }
            try {
                holders.put(xmlParser.parseResults(resultDir, true), resultDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (holders.isEmpty()) {
            printLine(String.format("No results found"));
            return;
        }
        int i = 0;
        for (SuiteResultHolder holder : holders.keySet()) {
            String moduleProgress = String.format("%d of %d",
                    holder.completeModules, holder.totalModules);

            table.add(
                    Arrays.asList(
                            Integer.toString(i),
                            Long.toString(holder.passedTests),
                            Long.toString(holder.failedTests),
                            moduleProgress,
                            holders.get(holder).getName(),
                            holder.context
                                    .getAttributes()
                                    .get(CertificationResultXml.SUITE_PLAN_ATTR)
                                    .get(0),
                            Joiner.on(", ").join(holder.context.getShardsSerials().values()),
                            printAttributes(holder.context.getAttributes(), "build_id"),
                            printAttributes(holder.context.getAttributes(), "build_product")));
            i++;
        }

        // add the table header to the beginning of the list
        table.add(0, Arrays.asList("Session", "Pass", "Fail", "Modules Complete",
                "Result Directory", "Test Plan", "Device serial(s)", "Build ID", "Product"));
        tableFormatter.displayTable(table, new PrintWriter(System.out, true));
    }

    private String printAttributes(MultiMap<String, String> map, String key) {
        if (map.get(key) == null) {
            return "unknown";
        }
        return map.get(key).get(0);
    }

    /**
     * Returns the list of all results directories.
     */
    private List<File> getResults(File resultsDir) {
        return ResultHandler.getResultDirectories(resultsDir);
    }

    private void listSubPlans() {
        File[] files = null;
        try {
            files = getBuildHelper().getSubPlansDir().listFiles();
        } catch (FileNotFoundException e) {
            printLine(e.getMessage());
            e.printStackTrace();
        }
        if (files != null && files.length > 0) {
            List<String> subPlans = new ArrayList<>();
            for (File subPlanFile : files) {
                subPlans.add(FileUtil.getBaseName(subPlanFile.getName()));
            }
            Collections.sort(subPlans);
            for (String subPlan : subPlans) {
                printLine(subPlan);
            }
        } else {
            printLine("No subplans found");
        }
    }

    private void addSubPlan(String[] flatArgs) {
        SubPlanHelper creator = new SubPlanHelper();
        try {
            ArgsOptionParser optionParser = new ArgsOptionParser(creator);
            optionParser.parse(Arrays.asList(flatArgs));
            creator.createAndSerializeSubPlan(getBuildHelper());
        } catch (ConfigurationException e) {
            printLine("Error: " + e.getMessage());
            printLine(ArgsOptionParser.getOptionHelp(false, creator));
        }

    }

    private CompatibilityBuildHelper getBuildHelper() {
        if (mBuildHelper == null) {
            IBuildInfo build = getBuild();
            if (build == null) {
                return null;
            }
            mBuildHelper = new CompatibilityBuildHelper(build);
        }
        return mBuildHelper;
    }

    private IBuildInfo getBuild() {
        if (mBuildInfo == null) {
            try {
                CompatibilityBuildProvider buildProvider = new CompatibilityBuildProvider();
                mBuildInfo = buildProvider.getBuild();
            } catch (BuildRetrievalError e) {
                e.printStackTrace();
            }
        }
        return mBuildInfo;
    }

    public static void main(String[] args) throws InterruptedException, ConfigurationException {
        Console console = new CompatibilityConsole();
        Console.startConsole(console, args);
    }
}
