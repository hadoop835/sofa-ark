/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.ark.container.service.biz;

import com.alipay.sofa.ark.api.ArkConfigs;
import com.alipay.sofa.ark.common.util.AssertUtils;
import com.alipay.sofa.ark.common.util.ClassLoaderUtils;
import com.alipay.sofa.ark.common.util.FileUtils;
import com.alipay.sofa.ark.common.util.StringUtils;
import com.alipay.sofa.ark.container.model.BizModel;
import com.alipay.sofa.ark.container.service.classloader.BizClassLoader;
import com.alipay.sofa.ark.loader.ExplodedBizArchive;
import com.alipay.sofa.ark.loader.DirectoryBizArchive;
import com.alipay.sofa.ark.loader.JarBizArchive;
import com.alipay.sofa.ark.loader.archive.JarFileArchive;
import com.alipay.sofa.ark.loader.jar.JarFile;
import com.alipay.sofa.ark.spi.archive.Archive;
import com.alipay.sofa.ark.spi.archive.BizArchive;
import com.alipay.sofa.ark.spi.constant.Constants;
import com.alipay.sofa.ark.spi.model.*;
import com.alipay.sofa.ark.spi.model.BizInfo.StateChangeReason;
import com.alipay.sofa.ark.spi.service.biz.BizFactoryService;
import com.alipay.sofa.ark.spi.service.plugin.PluginManagerService;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.stream.Stream;

import static com.alipay.sofa.ark.spi.constant.Constants.*;

/**
 * {@link BizFactoryService}
 *
 * @author qilong.zql
 * @since 0.4.0
 */
@Singleton
public class BizFactoryServiceImpl implements BizFactoryService {

    @Inject
    private PluginManagerService pluginManagerService;

    @Override
    public Biz createBiz(BizArchive bizArchive) throws IOException {
        return createBiz(bizArchive, new BizConfig());
    }

    @Override
    public Biz createBiz(BizArchive bizArchive, URL[] extensionUrls) throws IOException {
        BizConfig bizConfig = new BizConfig();
        bizConfig.setExtensionUrls(extensionUrls);
        return createBiz(bizArchive, bizConfig);
    }

    @Override
    public Biz createBiz(File file) throws IOException {
        BizArchive bizArchive = prepareBizArchive(file);
        return createBiz(bizArchive, new BizConfig());
    }

    @Override
    public Biz createBiz(File file, URL[] extensionUrls) throws IOException {
        BizArchive bizArchive = prepareBizArchive(file);
        BizConfig bizConfig = new BizConfig();
        bizConfig.setExtensionUrls(extensionUrls);
        return createBiz(bizArchive, bizConfig);
    }

    @Override
    public Biz createBiz(BizOperation bizOperation, File file) throws IOException {
        BizArchive bizArchive = prepareBizArchive(file);
        BizConfig bizConfig = new BizConfig();
        bizConfig.setSpecifiedVersion(bizOperation.getBizVersion());
        return createBiz(bizArchive, bizConfig);
    }

    @Override
    public Biz createBiz(File file, BizConfig bizConfig) throws IOException {
        BizArchive bizArchive = prepareBizArchive(file);
        return createBiz(bizArchive, bizConfig);
    }

    @Override
    public Biz createBiz(BizArchive bizArchive, BizConfig bizConfig) throws IOException {
        AssertUtils.isTrue(isArkBiz(bizArchive), "Archive must be a ark biz!");
        AssertUtils.isTrue(bizConfig != null, "BizConfig must not be null!");

        Attributes manifestMainAttributes = bizArchive.getManifest().getMainAttributes();
        String mainClass = manifestMainAttributes.getValue(MAIN_CLASS_ATTRIBUTE);
        String startClass = manifestMainAttributes.getValue(START_CLASS_ATTRIBUTE);
        BizModel bizModel = new BizModel();
        bizModel
            .setBizState(BizState.RESOLVED, StateChangeReason.CREATED)
            .setBizName(manifestMainAttributes.getValue(ARK_BIZ_NAME))
            .setBizVersion(
                !StringUtils.isEmpty(bizConfig.getSpecifiedVersion()) ? bizConfig
                    .getSpecifiedVersion() : manifestMainAttributes.getValue(ARK_BIZ_VERSION))
            .setBizUrl(!(bizArchive instanceof DirectoryBizArchive) ? bizArchive.getUrl() : null)
            .setMainClass(!StringUtils.isEmpty(startClass) ? startClass : mainClass)
            .setPriority(manifestMainAttributes.getValue(PRIORITY_ATTRIBUTE))
            .setWebContextPath(manifestMainAttributes.getValue(WEB_CONTEXT_PATH))
            .setDenyImportPackages(manifestMainAttributes.getValue(DENY_IMPORT_PACKAGES))
            .setDenyImportClasses(manifestMainAttributes.getValue(DENY_IMPORT_CLASSES))
            .setDenyImportResources(manifestMainAttributes.getValue(DENY_IMPORT_RESOURCES))
            .setInjectPluginDependencies(
                getInjectDependencies(manifestMainAttributes.getValue(INJECT_PLUGIN_DEPENDENCIES)))
            .setInjectExportPackages(manifestMainAttributes.getValue(INJECT_EXPORT_PACKAGES))
            .setDeclaredLibraries(manifestMainAttributes.getValue(DECLARED_LIBRARIES))
            .setClassPath(getMergedBizClassPath(bizArchive.getUrls(), bizConfig.getExtensionUrls()));

        // prepare dependent plugins and plugin export map
        List<String> dependentPlugins = bizConfig.getDependentPlugins();
        if (dependentPlugins == null || dependentPlugins.isEmpty()) {
            dependentPlugins = StringUtils.strToList(
                manifestMainAttributes.getValue("dependent-plugins"),
                Constants.MANIFEST_VALUE_SPLIT);
        }
        resolveExportMapIfNecessary(bizModel, dependentPlugins);

        // must be after prepare dependent plugins
        bizModel.setPluginClassPath(getPluginURLs(bizModel));

        // create biz classloader
        BizClassLoader bizClassLoader = new BizClassLoader(bizModel.getIdentity(),
            getBizUcp(bizModel), bizArchive instanceof ExplodedBizArchive
                                 || bizArchive instanceof DirectoryBizArchive);
        bizClassLoader.setBizModel(bizModel);
        bizModel.setClassLoader(bizClassLoader);

        // set biz work dir
        if (bizModel.getBizUrl() != null) {
            bizModel.setBizTempWorkDir(new File(bizModel.getBizUrl().getFile()));
        }

        return bizModel;
    }

    @Override
    public Biz createEmbedMasterBiz(ClassLoader masterClassLoader) {
        BizModel bizModel = new BizModel();
        bizModel.setBizState(BizState.RESOLVED, StateChangeReason.CREATED)
            .setBizName(ArkConfigs.getStringValue(MASTER_BIZ)).setBizVersion("1.0.0")
            .setMainClass("embed main").setPriority("100").setWebContextPath("/")
            .setDenyImportPackages(null).setDenyImportClasses(null).setDenyImportResources(null)
            .setInjectPluginDependencies(new HashSet<>()).setInjectExportPackages(null)
            .setClassPath(ClassLoaderUtils.getURLs(masterClassLoader))
            .setClassLoader(masterClassLoader);
        return bizModel;
    }

    private BizArchive prepareBizArchive(File file) throws IOException {
        BizArchive bizArchive;
        boolean unpackBizWhenInstall = Boolean.parseBoolean(ArkConfigs.getStringValue(
            UNPACK_BIZ_WHEN_INSTALL, "true"));
        if (ArkConfigs.isEmbedEnable() && unpackBizWhenInstall) {
            File unpackFile = FileUtils.file(file.getAbsolutePath() + "-unpack");
            if (!unpackFile.exists()) {
                unpackFile = FileUtils.unzip(file, file.getAbsolutePath() + "-unpack");
            }
            if (file.exists()) {
                file.delete();
            }
            file = unpackFile;
            bizArchive = new ExplodedBizArchive(unpackFile);
        } else {
            JarFile bizFile = new JarFile(file);
            JarFileArchive jarFileArchive = new JarFileArchive(bizFile);
            bizArchive = new JarBizArchive(jarFileArchive);
        }
        return bizArchive;
    }

    private URL[] getMergedBizClassPath(URL[] bizArchiveUrls, URL[] extensionUrls) {
        if (extensionUrls == null || extensionUrls.length == 0) {
            return bizArchiveUrls;
        }
        return Stream.concat(Arrays.stream(bizArchiveUrls), Arrays.stream(extensionUrls)).toArray(URL[]::new);
    }

    private void resolveExportMapIfNecessary(BizModel bizModel, List<String> dependentPlugins) {
        Set<Plugin> plugins = new HashSet<>();
        if (ArkConfigs.isBizSpecifyDependentPluginsEnable()) {
            if (dependentPlugins != null && !dependentPlugins.isEmpty()) {
                for (String pluginName : dependentPlugins) {
                    Plugin plugin = pluginManagerService.getPluginByName(pluginName);
                    plugins.add(plugin);
                }
            }
        } else {
            plugins.addAll(pluginManagerService.getPluginsInOrder());
        }

        bizModel.setDependentPlugins(plugins);
        for (Plugin plugin : plugins) {
            for (String exportIndex : plugin.getExportPackageNodes()) {
                bizModel.getExportNodeAndClassLoaderMap().putIfAbsent(exportIndex, plugin);
            }
            for (String exportIndex : plugin.getExportPackageStems()) {
                bizModel.getExportStemAndClassLoaderMap().putIfAbsent(exportIndex, plugin);
            }
            for (String exportIndex : plugin.getExportClasses()) {
                bizModel.getExportClassAndClassLoaderMap().putIfAbsent(exportIndex, plugin);
            }
            for (String resource : plugin.getExportResources()) {
                bizModel.getExportResourceAndClassLoaderMap().putIfAbsent(resource,
                    new LinkedList<>());
                bizModel.getExportResourceAndClassLoaderMap().get(resource).add(plugin);
            }
            for (String resource : plugin.getExportPrefixResourceStems()) {
                bizModel.getExportPrefixStemResourceAndClassLoaderMap().putIfAbsent(resource,
                    new LinkedList<>());
                bizModel.getExportPrefixStemResourceAndClassLoaderMap().get(resource).add(plugin);
            }
            for (String resource : plugin.getExportSuffixResourceStems()) {
                bizModel.getExportSuffixStemResourceAndClassLoaderMap().putIfAbsent(resource,
                    new LinkedList<>());
                bizModel.getExportSuffixStemResourceAndClassLoaderMap().get(resource).add(plugin);
            }
        }
    }

    private Set<String> getInjectDependencies(String injectPluginDependencies) {
        Set<String> dependencies = new HashSet<>();
        if (StringUtils.strToSet(injectPluginDependencies, Constants.MANIFEST_VALUE_SPLIT) != null) {
            dependencies.addAll(StringUtils.strToSet(injectPluginDependencies,
                Constants.MANIFEST_VALUE_SPLIT));
        }
        return dependencies;
    }

    private boolean isArkBiz(BizArchive bizArchive) {
        if (ArkConfigs.isEmbedEnable() && bizArchive instanceof ExplodedBizArchive) {
            return true;
        }
        return bizArchive.isEntryExist(new Archive.EntryFilter() {
            @Override
            public boolean matches(Archive.Entry entry) {
                return !entry.isDirectory() && entry.getName().equals(Constants.ARK_BIZ_MARK_ENTRY);
            }
        });
    }

    private URL[] getBizUcp(BizModel bizModel) {
        List<URL> bizUcp = new ArrayList<>();
        bizUcp.addAll(Arrays.asList(bizModel.getClassPath()));
        bizUcp.addAll(Arrays.asList(getPluginURLs(bizModel)));
        return bizUcp.toArray(new URL[bizUcp.size()]);
    }

    private URL[] getPluginURLs(BizModel bizModel) {
        List<URL> pluginUrls = new ArrayList<>();
        for (Plugin plugin : bizModel.getDependentPlugins()) {
            pluginUrls.add(plugin.getPluginURL());
        }
        return pluginUrls.toArray(new URL[pluginUrls.size()]);
    }
}
