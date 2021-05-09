/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.testtype.suite.params;

import com.android.tradefed.testtype.suite.params.multiuser.RunOnSecondaryUserParameterHandler;
import com.android.tradefed.testtype.suite.params.multiuser.RunOnWorkProfileParameterHandler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Helper to get the {@link IModuleParameter} associated with the parameter. */
public class ModuleParametersHelper {

    private static Map<ModuleParameters, IModuleParameter> sHandlerMap = new HashMap<>();

    static {
        sHandlerMap.put(ModuleParameters.INSTANT_APP, new InstantAppHandler());
        sHandlerMap.put(ModuleParameters.NOT_INSTANT_APP, new NegativeHandler());

        sHandlerMap.put(ModuleParameters.MULTI_ABI, new NegativeHandler());
        sHandlerMap.put(ModuleParameters.NOT_MULTI_ABI, new NotMultiAbiHandler());

        sHandlerMap.put(
                ModuleParameters.RUN_ON_WORK_PROFILE, new RunOnWorkProfileParameterHandler());
        sHandlerMap.put(
                ModuleParameters.RUN_ON_SECONDARY_USER, new RunOnSecondaryUserParameterHandler());
    }

    private static Map<ModuleParameters, Set<ModuleParameters>> sGroupMap = new HashMap<>();

    static {
        sGroupMap.put(
                ModuleParameters.MULTIUSER,
                Set.of(
                        ModuleParameters.RUN_ON_WORK_PROFILE,
                        ModuleParameters.RUN_ON_SECONDARY_USER));
    }

    /**
     * Optional parameters are params that will not automatically be created when the module
     * parameterization is enabled. They will need to be explicitly enabled. They represent a second
     * set of parameterization that is less commonly requested to run. They could be upgraded to
     * main parameters in the future by moving them above.
     */
    private static Map<ModuleParameters, IModuleParameter> sOptionalHandlerMap = new HashMap<>();

    static {
        sOptionalHandlerMap.put(ModuleParameters.SECONDARY_USER, new SecondaryUserHandler());
        sOptionalHandlerMap.put(ModuleParameters.NOT_SECONDARY_USER, new NegativeHandler());
    }

    private static Map<ModuleParameters, Set<ModuleParameters>> sOptionalGroupMap = new HashMap<>();

    static {
    }

    /**
     * Returns the {@link IModuleParameter} associated with the requested parameter.
     *
     * @param withOptional Whether or not to also check optional params.
     */
    public static IModuleParameter getParameterHandler(
            ModuleParameters param, boolean withOptional) {
        IModuleParameter value = sHandlerMap.get(param);
        if (value == null && withOptional) {
            return sOptionalHandlerMap.get(param);
        }
        return value;
    }

    /**
     * Resolve a {@link ModuleParameters} from its {@link String} representation.
     *
     * @see #resolveParam(ModuleParameters, boolean)
     */
    public static Set<ModuleParameters> resolveParam(String param, boolean withOptional) {
        return resolveParam(ModuleParameters.valueOf(param.toUpperCase()), withOptional);
    }

    /**
     * Get the all {@link ModuleParameters} which are sub-params of a given {@link
     * ModuleParameters}.
     *
     * <p>This will recursively resolve sub-groups and will only return {@link ModuleParameters}
     * which are not groups.
     *
     * <p>If {@code param} is not a group then a singleton set containing {@code param} will be
     * returned itself, regardless of {@code withOptional}.
     *
     * @param withOptional Whether or not to also check optional param groups.
     */
    public static Set<ModuleParameters> resolveParam(ModuleParameters param, boolean withOptional) {
        Set<ModuleParameters> mappedParams = sGroupMap.get(param);
        if (mappedParams == null && withOptional) {
            mappedParams = sOptionalGroupMap.get(param);
        }
        if (mappedParams != null) {
            Set<ModuleParameters> resolvedParams = new HashSet<>();
            for (ModuleParameters moduleParameters : mappedParams) {
                resolvedParams.addAll(resolveParam(moduleParameters, withOptional));
            }
            return resolvedParams;
        }

        return Set.of(param);
    }
}
