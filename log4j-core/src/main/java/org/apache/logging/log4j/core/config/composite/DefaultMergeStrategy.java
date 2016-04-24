/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.config.composite;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.apache.logging.log4j.core.config.plugins.util.PluginType;
import org.apache.logging.log4j.core.filter.CompositeFilter;

/**
 * The default merge strategy for composite configurations.
 * <p>
 * The default merge strategy performs the merge according to the following rules:
 * <ol>
 * <li>Aggregates the global configuration attributes with those in later configurations replacing those in previous
 * configurations with the exception that the highest status level and the lowest monitorInterval greater than 0 will
 * be used.</li>
 * <li>Properties from all configurations are aggregated. Duplicate properties replace those in previous
 * configurations.</li>
 * <li>Filters are aggregated under a CompositeFilter if more than one Filter is defined. Since Filters are not named
 * duplicates may be present.</li>
 * <li>Scripts and ScriptFile references are aggregated. Duplicate definiations replace those in previous
 * configurations.</li>
 * <li>Appenders are aggregated. Appenders with the same name are replaced by those in later configurations, including
 * all of the Appender's subcomponents.</li>
 * <li>Loggers are all aggregated. Logger attributes are individually merged with duplicates being replaced by those
 * in later configurations. Appender references on a Logger are aggregated with duplicates being replaced by those in
 * later configurations. Filters on a Logger are aggregated under a CompositeFilter if more than one Filter is defined.
 * Since Filters are not named duplicates may be present. Filters under Appender references included or discarded
 * depending on whether their parent Appender reference is kept or discarded.</li>
 * </ol>
 */
public class DefaultMergeStrategy implements MergeStrategy {

    private static final String APPENDERS = "appenders";
    private static final String PROPERTIES = "properties";
    private static final String LOGGERS = "loggers";
    private static final String SCRIPTS = "scripts";
    private static final String FILTERS = "filters";
    private static final String STATUS = "status";
    private static final String NAME = "name";
    private static final String REF = "ref";

    /**
     * Merge the source Configuration into the target Configuration.
     *
     * @param target        The target node to merge into.
     * @param source        The source node.
     * @param pluginManager The PluginManager.
     */
    public void mergConfigurations(Node target, Node source, PluginManager pluginManager) {
        for (Map.Entry<String, String> attribute : source.getAttributes().entrySet()) {
            boolean isFound = false;
            for (Map.Entry<String, String> targetAttribute : target.getAttributes().entrySet()) {
                if (targetAttribute.getKey().equalsIgnoreCase(attribute.getKey())) {
                    if (attribute.getKey().equalsIgnoreCase(STATUS)) {
                        Level targetLevel = Level.getLevel(targetAttribute.getValue());
                        Level sourceLevel = Level.getLevel(attribute.getValue());
                        if (targetLevel != null && sourceLevel != null) {
                            if (sourceLevel.isLessSpecificThan(targetLevel)) {
                                targetAttribute.setValue(attribute.getValue());
                            }
                        }
                    } else {
                        if (attribute.getKey().equalsIgnoreCase("monitorInterval")) {
                            int sourceInterval = Integer.parseInt(attribute.getValue());
                            int targetInterval = Integer.parseInt(targetAttribute.getValue());
                            if (targetInterval == 0 || sourceInterval < targetInterval) {
                                targetAttribute.setValue(attribute.getValue());
                            }
                        } else {
                            targetAttribute.setValue(attribute.getValue());
                        }
                    }
                    isFound = true;
                }
            }
            if (!isFound) {
                target.getAttributes().put(attribute.getKey(), attribute.getValue());
            }
        }
        for (Node sourceChildNode : source.getChildren()) {
            boolean isFilter = isFilterNode(sourceChildNode);
            boolean isMerged = false;
            for (Node targetChildNode : target.getChildren()) {
                if (isFilter) {
                    if (isFilterNode(targetChildNode)) {
                        updateFilterNode(target, targetChildNode, sourceChildNode, pluginManager);
                        isMerged = true;
                        break;
                    } else {
                        continue;
                    }
                }

                if (!targetChildNode.getName().equalsIgnoreCase(sourceChildNode.getName())) {
                    continue;
                }

                switch (targetChildNode.getName().toLowerCase()) {
                    case PROPERTIES:
                    case SCRIPTS:
                    case APPENDERS: {
                        for (Node node : sourceChildNode.getChildren()) {
                            for (Node targetNode : targetChildNode.getChildren()) {
                                if (targetNode.getAttributes().get(NAME).equals(node.getAttributes().get(NAME))) {
                                    targetChildNode.getChildren().remove(targetNode);
                                    break;
                                }
                            }
                            targetChildNode.getChildren().add(node);
                        }
                        isMerged = true;
                        break;
                    }
                    case LOGGERS: {
                        Map<String, Node> targetLoggers = new HashMap<>();
                        for (Node node : targetChildNode.getChildren()) {
                            targetLoggers.put(node.getName(), node);
                        }
                        for (Node node : sourceChildNode.getChildren()) {
                            Node targetNode = getLoggerNode(targetChildNode, node.getAttributes().get(NAME));
                            Node loggerNode = new Node(targetChildNode, node.getName(), node.getType());
                            if (targetNode != null) {
                                for (Node sourceLoggerChild : node.getChildren()) {
                                    if (isFilterNode(sourceLoggerChild)) {
                                        boolean foundFilter = false;
                                        for (Node targetChild : targetNode.getChildren()) {
                                            if (isFilterNode(targetChild)) {
                                                updateFilterNode(loggerNode, targetChild, sourceLoggerChild,
                                                        pluginManager);
                                                foundFilter = true;
                                                break;
                                            }
                                        }
                                        if (!foundFilter) {
                                            Node childNode = new Node(loggerNode, sourceLoggerChild.getName(),
                                                    sourceLoggerChild.getType());
                                            targetNode.getChildren().add(childNode);
                                        }
                                    } else {
                                        Node childNode = new Node(loggerNode, sourceLoggerChild.getName(),
                                                sourceLoggerChild.getType());
                                        childNode.getAttributes().putAll(sourceLoggerChild.getAttributes());
                                        if (childNode.getName().equalsIgnoreCase("AppenderRef")) {
                                            for (Node targetChild : targetNode.getChildren()) {
                                                if (isSameReference(targetChild, childNode)) {
                                                    targetNode.getChildren().remove(targetChild);
                                                    break;
                                                }
                                            }
                                        } else {
                                            for (Node targetChild : targetNode.getChildren()) {
                                                if (isSameName(targetChild, childNode)) {
                                                    targetNode.getChildren().remove(targetChild);
                                                    break;
                                                }
                                            }
                                        }

                                        targetNode.getChildren().add(childNode);
                                    }
                                }
                            } else {
                                loggerNode.getAttributes().putAll(node.getAttributes());
                                loggerNode.getChildren().addAll(node.getChildren());
                                targetChildNode.getChildren().add(loggerNode);
                            }
                        }
                        isMerged = true;
                        break;
                    }
                    default: {
                        targetChildNode.getChildren().addAll(sourceChildNode.getChildren());
                        isMerged = true;
                        break;
                    }

                }
            }
            if (!isMerged) {
                target.getChildren().add(sourceChildNode);
            }
        }
    }

    private Node getLoggerNode(Node parentNode, String name) {
        for (Node node : parentNode.getChildren()) {
            String nodeName = node.getAttributes().get(NAME);
            if (name == null && nodeName == null) {
                return node;
            }
            if (nodeName != null && nodeName.equals(name)) {
                return node;
            }
        }
        return null;
    }

    private void updateFilterNode(Node target, Node targetChildNode, Node sourceChildNode,
            PluginManager pluginManager) {
        if (CompositeFilter.class.isAssignableFrom(targetChildNode.getType().getPluginClass())) {
            Node node = new Node(targetChildNode, sourceChildNode.getName(), sourceChildNode.getType());
            node.getChildren().addAll(sourceChildNode.getChildren());
            node.getAttributes().putAll(sourceChildNode.getAttributes());
            targetChildNode.getChildren().add(node);
        } else {
            PluginType pluginType = pluginManager.getPluginType(FILTERS);
            Node filtersNode = new Node(targetChildNode, FILTERS, pluginType);
            Node node = new Node(filtersNode, sourceChildNode.getName(), sourceChildNode.getType());
            node.getAttributes().putAll(sourceChildNode.getAttributes());
            List<Node> children = filtersNode.getChildren();
            children.add(targetChildNode);
            children.add(node);
            List<Node> nodes = target.getChildren();
            nodes.remove(targetChildNode);
            nodes.add(filtersNode);
        }
    }

    private boolean isFilterNode(Node node) {
        return Filter.class.isAssignableFrom(node.getType().getPluginClass());
    }

    private boolean isSameName(Node node1, Node node2) {
        return node1.getAttributes().get(NAME).toLowerCase().equals(node2.getAttributes().get(NAME).toLowerCase());
    }

    private boolean isSameReference(Node node1, Node node2) {
        return node1.getAttributes().get(REF).toLowerCase().equals(node2.getAttributes().get(REF).toLowerCase());
    }
}
