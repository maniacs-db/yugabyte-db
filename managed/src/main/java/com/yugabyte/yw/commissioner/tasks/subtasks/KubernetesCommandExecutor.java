// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.subtasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.common.KubernetesManager;
import com.yugabyte.yw.common.ShellProcessHandler;
import com.yugabyte.yw.forms.AbstractTaskParams;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import play.Application;
import play.api.Play;
import play.libs.Json;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KubernetesCommandExecutor extends AbstractTaskBase {
  public enum CommandType {
    CREATE_NAMESPACE,
    APPLY_SECRET,
    HELM_INIT,
    HELM_INSTALL,
    HELM_UPGRADE,
    UPDATE_NUM_NODES,
    HELM_DELETE,
    VOLUME_DELETE,
    NAMESPACE_DELETE,
    POD_INFO;

    public String getSubTaskGroupName() {
      switch (this) {
        case CREATE_NAMESPACE:
          return UserTaskDetails.SubTaskGroupType.CreateNamespace.name();
        case APPLY_SECRET:
          return UserTaskDetails.SubTaskGroupType.ApplySecret.name();
        case HELM_INIT:
          return UserTaskDetails.SubTaskGroupType.HelmInit.name();
        case HELM_INSTALL:
          return UserTaskDetails.SubTaskGroupType.HelmInstall.name();
        case HELM_UPGRADE:
          return UserTaskDetails.SubTaskGroupType.HelmUpgrade.name();
        case UPDATE_NUM_NODES:
          return UserTaskDetails.SubTaskGroupType.UpdateNumNodes.name();
        case HELM_DELETE:
          return UserTaskDetails.SubTaskGroupType.HelmDelete.name();
        case VOLUME_DELETE:
          return UserTaskDetails.SubTaskGroupType.KubernetesVolumeDelete.name();
        case NAMESPACE_DELETE:
          return UserTaskDetails.SubTaskGroupType.KubernetesNamespaceDelete.name(); 
        case POD_INFO:
          return UserTaskDetails.SubTaskGroupType.KubernetesPodInfo.name();
      }
      return null;
    }
  }

  @Inject
  KubernetesManager kubernetesManager;

  @Inject
  Application application;

  static final Pattern nodeNamePattern = Pattern.compile(".*-n(\\d+)+");

  // Added constant to compute CPU burst limit
  static final double burstVal = 1.2;

  @Override
  public void initialize(ITaskParams params) {
    this.kubernetesManager = Play.current().injector().instanceOf(KubernetesManager.class);
    this.application = Play.current().injector().instanceOf(Application.class);
    super.initialize(params);
  }

  public static class Params extends AbstractTaskParams {
    public UUID providerUUID;
    public CommandType commandType;
    public UUID universeUUID;
    // We use the nodePrefix as Helm Chart's release name,
    // so we would need that for any sort helm operations.
    public String nodePrefix;
    public String ybSoftwareVersion = null;
    public ServerType serverType = ServerType.EITHER;
    public int rollingUpgradePartition = 0;
  }

  protected KubernetesCommandExecutor.Params taskParams() {
    return (KubernetesCommandExecutor.Params)taskParams;
  }

  @Override
  public void run() {
    String overridesFile;
    boolean flag = false;
    // TODO: add checks for the shell process handler return values.
    ShellProcessHandler.ShellResponse response = null;
    switch (taskParams().commandType) {
      case CREATE_NAMESPACE:
        response = kubernetesManager.createNamespace(taskParams().providerUUID, taskParams().nodePrefix);
        break;
      case APPLY_SECRET:
        String pullSecret = this.getPullSecret();
        if (pullSecret != null) {
          response = kubernetesManager.applySecret(taskParams().providerUUID, taskParams().nodePrefix, pullSecret);
        }
        break;
      case HELM_INIT:
        response = kubernetesManager.helmInit(taskParams().providerUUID);
        break;
      case HELM_INSTALL:
        overridesFile = this.generateHelmOverride();
        response = kubernetesManager.helmInstall(taskParams().providerUUID, taskParams().nodePrefix, overridesFile);
        flag = true;
        break;
      case HELM_UPGRADE:
        overridesFile = this.generateHelmOverride();
        response = kubernetesManager.helmUpgrade(taskParams().providerUUID, taskParams().nodePrefix, overridesFile);
        flag = true;
        break;
      case UPDATE_NUM_NODES:
        int numNodes = this.getNumNodes();
        if (numNodes > 0) {
          response = kubernetesManager.updateNumNodes(taskParams().providerUUID, taskParams().nodePrefix, numNodes);
        }
        break;
      case HELM_DELETE:
        kubernetesManager.helmDelete(taskParams().providerUUID, taskParams().nodePrefix);
        break;
      case VOLUME_DELETE:
        kubernetesManager.deleteStorage(taskParams().providerUUID, taskParams().nodePrefix);
        break;
      case NAMESPACE_DELETE:
        kubernetesManager.deleteNamespace(taskParams().providerUUID, taskParams().nodePrefix);
        break; 
      case POD_INFO:
        processNodeInfo();
        break;
    }
    if (response != null) {
      if (response.code != 0 && flag) {
        response = getPodError();
      }
      logShellResponse(response);
    }
  }

  private ShellProcessHandler.ShellResponse getPodError() {
    ShellProcessHandler.ShellResponse response = new ShellProcessHandler.ShellResponse();
    response.code = -1;
    ShellProcessHandler.ShellResponse podResponse = kubernetesManager.getPodInfos(taskParams().providerUUID, taskParams().nodePrefix);
    JsonNode podInfos = parseShellResponseAsJson(podResponse);
    boolean flag = false;
    for (JsonNode podInfo: podInfos.path("items")) {
      flag = true;
      ObjectNode pod = Json.newObject();
      JsonNode statusNode =  podInfo.path("status");
      String podStatus = statusNode.path("phase").asText();
      if (!podStatus.equals("Running")) {
        JsonNode podConditions = statusNode.path("conditions");
        ArrayList conditions = Json.fromJson(podConditions, ArrayList.class);
        Iterator iter = conditions.iterator();
        while (iter.hasNext()) {
          JsonNode info = Json.toJson(iter.next());
          String status = info.path("status").asText();
          if (status.equals("False")) {
            response.message = info.path("message").asText();
            return response;
          }
        }
      }      
    }
    if (!flag) {
      response.message = "No pods even scheduled. Previous step(s) incomplete";
    }
    else {
      response.message = "Pods are ready. Services still not running";
    }
    return response;
  }

  private void processNodeInfo() {
    ShellProcessHandler.ShellResponse podResponse = kubernetesManager.getPodInfos(taskParams().providerUUID, taskParams().nodePrefix);
    JsonNode podInfos = parseShellResponseAsJson(podResponse);
    ObjectNode pods = Json.newObject();
    // TODO: add more validations around the pod info call, handle error conditions
    for (JsonNode podInfo: podInfos.path("items")) {
      ObjectNode pod = Json.newObject();
      JsonNode statusNode =  podInfo.path("status");
      JsonNode podSpec = podInfo.path("spec");
      pod.put("startTime", statusNode.path("startTime").asText());
      pod.put("status", statusNode.path("phase").asText());
      // TODO: change the podIP to use cname ENG-3490, we need related jira ENG-3491 as well done.
      pod.put("privateIP", statusNode.get("podIP").asText());
      pods.set(podSpec.path("hostname").asText(), pod);
    }

    Universe.UniverseUpdater updater = universe -> {
      UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
      Set<NodeDetails> defaultNodes = universeDetails.nodeDetailsSet;
      NodeDetails defaultNode = defaultNodes.iterator().next();
      Set<NodeDetails> nodeDetailsSet = new HashSet<>();
      Iterator<Map.Entry<String, JsonNode>> iter = pods.fields();
      while (iter.hasNext()) {
        NodeDetails nodeDetail = defaultNode.clone();
        Map.Entry<String, JsonNode> pod = iter.next();
        String hostname = pod.getKey();
        JsonNode podVals = pod.getValue();
        if (hostname.contains("master")) {
          nodeDetail.isTserver = false;
          nodeDetail.isMaster = true;
        }
        else {
          nodeDetail.isMaster = false;
          nodeDetail.isTserver = true;
        }
        nodeDetail.cloudInfo.private_ip = podVals.get("privateIP").asText();
        nodeDetail.state = NodeDetails.NodeState.Live;
        nodeDetail.nodeName = hostname;
        nodeDetailsSet.add(nodeDetail);
      }
      universeDetails.nodeDetailsSet = nodeDetailsSet;
      universe.setUniverseDetails(universeDetails);
    };
    Universe.saveDetails(taskParams().universeUUID, updater);
  }

  private String nodeNameToPodName(String nodeName, boolean isMaster) {
    Matcher matcher = nodeNamePattern.matcher(nodeName);
    if (!matcher.matches()) {
      throw new RuntimeException("Invalid nodeName : " + nodeName);
    }
    int nodeIdx = Integer.parseInt(matcher.group(1));
    return String.format("%s-%d", isMaster ? "yb-master": "yb-tserver", nodeIdx - 1);
  }

  private String getPullSecret() {
    Provider provider = Provider.get(taskParams().providerUUID);
    if (provider != null) {
      Map<String, String> config = provider.getConfig();
      if (config.containsKey("KUBECONFIG_IMAGE_PULL_SECRET_NAME")) {
        return config.get("KUBECONFIG_PULL_SECRET");
      }
    }
    return null;
  }

  private int getNumNodes() {
    Provider provider = Provider.get(taskParams().providerUUID);
    if (provider != null) {
      Universe u = Universe.get(taskParams().universeUUID);
      UniverseDefinitionTaskParams.UserIntent userIntent =
          u.getUniverseDetails().getPrimaryCluster().userIntent;
      return userIntent.numNodes;
    }
    return -1;
  }

  private String generateHelmOverride() {
    Map<String, Object> overrides = new HashMap<String, Object>();
    Yaml yaml = new Yaml();
    // TODO: decide if the user want to expose all the services or just master
    overrides =(HashMap<String, Object>) yaml.load(
        application.resourceAsStream("k8s-expose-all.yml")
    );

    Provider provider = Provider.get(taskParams().providerUUID);
    Map<String, String> config = provider.getConfig();

    Universe u = Universe.get(taskParams().universeUUID);
    // TODO: This only takes into account primary cluster for Kuberentes, we need to
    // address ReadReplica clusters as well.
    UniverseDefinitionTaskParams.UserIntent userIntent =
        u.getUniverseDetails().getPrimaryCluster().userIntent;
    InstanceType instanceType = InstanceType.get(userIntent.providerType, userIntent.instanceType);
    if (instanceType == null) {
      LOG.error("Unable to fetch InstanceType for {}, {}",
          userIntent.providerType, userIntent.instanceType);
      throw new RuntimeException("Unable to fetch InstanceType " + userIntent.providerType +
          ": " +  userIntent.instanceType);
    }

    // Override disk count and size according to user intent.
    Map<String, Object> diskSpecs = new HashMap<>();
    if (userIntent.deviceInfo != null) {
      if (userIntent.deviceInfo.numVolumes != null) {
        diskSpecs.put("count", userIntent.deviceInfo.numVolumes);
      }
      if (userIntent.deviceInfo.volumeSize != null) {
        diskSpecs.put("storage", String.format("%dGi", userIntent.deviceInfo.volumeSize));
      }
      if (userIntent.deviceInfo.storageClass != null) {
        diskSpecs.put("storageClass", userIntent.deviceInfo.storageClass);
      }
      if (!diskSpecs.isEmpty()) {
        overrides.put("persistentVolume", diskSpecs);
      }
    }

    // Override resource request and limit based on instance type.
    Map<String, Object> tserverResource = new HashMap<>();
    Map<String, Object> tserverLimit = new HashMap<>();
    tserverResource.put("cpu", instanceType.numCores);
    tserverResource.put("memory", String.format("%.2fGi", instanceType.memSizeGB));
    tserverLimit.put("cpu", instanceType.numCores * burstVal);
    tserverLimit.put("memory", String.format("%.2fGi", instanceType.memSizeGB));
    Map<String, Object> resourceOverrides = new HashMap();
    resourceOverrides.put("tserver", ImmutableMap.of("requests", tserverResource, "limits", tserverLimit));

    // If the instance type is not xsmall or dev, we would bump the master resource.
    if (!instanceType.getInstanceTypeCode().equals("xsmall") &&
        !instanceType.getInstanceTypeCode().equals("dev") ) {
      Map<String, Object> masterResource = new HashMap<>();
      Map<String, Object> masterLimit = new HashMap<>();
      masterResource.put("cpu", 2);
      masterResource.put("memory", "4Gi");
      masterLimit.put("cpu", 2 * burstVal);
      masterLimit.put("memory", "4Gi");
      resourceOverrides.put("master", ImmutableMap.of("requests", masterResource, "limits", masterLimit));
    }

    overrides.put("resource", resourceOverrides);

    Map<String, Object> imageInfo = new HashMap<>();
    // Override image tag based on ybsoftwareversion.
    String imageTag = taskParams().ybSoftwareVersion == null ? userIntent.ybSoftwareVersion : taskParams().ybSoftwareVersion;
    imageInfo.put("tag", imageTag);
    if (config.containsKey("KUBECONFIG_IMAGE_REGISTRY")) {
      imageInfo.put("repository", config.get("KUBECONFIG_IMAGE_REGISTRY"));
    }
    if (config.containsKey("KUBECONFIG_IMAGE_PULL_SECRET_NAME")) {
      imageInfo.put("pullSecretName", config.get("KUBECONFIG_IMAGE_PULL_SECRET_NAME"));
    }
    overrides.put("Image", imageInfo);

    Map<String, Object> partition = new HashMap<>();
    if (taskParams().serverType == ServerType.TSERVER) {
      partition.put("tserver", taskParams().rollingUpgradePartition);
      partition.put("master", userIntent.replicationFactor);
    }
    else if (taskParams().serverType == ServerType.MASTER) {
      partition.put("tserver", userIntent.numNodes);
      partition.put("master", taskParams().rollingUpgradePartition);
    }
    if (!partition.isEmpty()) {
      overrides.put("partition", partition);
    }

    // Override num of tserver replicas based on num nodes
    // and num of master replicas based on replication factor.
    overrides.put("replicas", ImmutableMap.of("tserver", userIntent.numNodes,
        "master", userIntent.replicationFactor));

    // TODO: this needs a better handling in k8s.
    String placementCloud = null;
    String placementRegion = null;
    String placementZone = null;
    UUID placementUuid = u.getUniverseDetails().getPrimaryCluster().uuid;
    PlacementInfo pi = u.getUniverseDetails().getPrimaryCluster().placementInfo;
    if (pi != null) {
      if (pi.cloudList.size() != 0) {
        PlacementInfo.PlacementCloud cloud = pi.cloudList.get(0);
        placementCloud = cloud.code;
        if (cloud.regionList.size() != 0) {
          PlacementInfo.PlacementRegion region = cloud.regionList.get(0);
          placementRegion = region.code;
          if (region.azList.size() != 0) {
            PlacementInfo.PlacementAZ zone = region.azList.get(0);
            // TODO: wtf, why do we have AZ name but not code at this level??
            placementZone = AvailabilityZone.get(zone.uuid).code;
          }
        }
      }
    }

    Map<String, Object> gflagOverrides = new HashMap<>();
    // Go over master flags.
    Map<String, Object> masterOverrides = new HashMap<String, Object>(userIntent.masterGFlags);
    if (placementCloud != null && masterOverrides.get("placement_cloud") == null) {
      masterOverrides.put("placement_cloud", placementCloud);
    }
    if (placementRegion != null && masterOverrides.get("placement_region") == null) {
      masterOverrides.put("placement_region", placementRegion);
    }
    if (placementZone != null && masterOverrides.get("placement_zone") == null) {
      masterOverrides.put("placement_zone", placementZone);
    }
    if (placementUuid != null && masterOverrides.get("placement_uuid") == null) {
      masterOverrides.put("placement_uuid", placementUuid.toString());
    }
    if (!masterOverrides.isEmpty()) {
      gflagOverrides.put("master", masterOverrides);
    }
    // Go over master flags.
    Map<String, Object> tserverOverrides = new HashMap<String, Object>(userIntent.tserverGFlags);
    if (placementCloud != null && tserverOverrides.get("placement_cloud") == null) {
      tserverOverrides.put("placement_cloud", placementCloud);
    }
    if (placementRegion != null && tserverOverrides.get("placement_region") == null) {
      tserverOverrides.put("placement_region", placementRegion);
    }
    if (placementZone != null && tserverOverrides.get("placement_zone") == null) {
      tserverOverrides.put("placement_zone", placementZone);
    }
    if (placementUuid != null && tserverOverrides.get("placement_uuid") == null) {
      tserverOverrides.put("placement_uuid", placementUuid.toString());
    }
    if (!tserverOverrides.isEmpty()) {
      gflagOverrides.put("tserver", tserverOverrides);
    }

    if (!gflagOverrides.isEmpty()) {
      overrides.put("gflags", gflagOverrides);
    }

    Map<String, Object> annotations = new HashMap<String, Object>();
    if (config.containsKey("KUBECONFIG_ANNOTATIONS")) {
      annotations =(HashMap<String, Object>) yaml.load(
          config.get("KUBECONFIG_ANNOTATIONS"));
      overrides.putAll(annotations);
    }

    try {
      Path tempFile = Files.createTempFile(taskParams().universeUUID.toString(), ".yml");
      BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile.toFile()));
      yaml.dump(overrides, bw);
      return tempFile.toAbsolutePath().toString();
    } catch (IOException e) {
      LOG.error(e.getMessage());
      throw new RuntimeException("Error writing Helm Override file!");
    }
  }
}