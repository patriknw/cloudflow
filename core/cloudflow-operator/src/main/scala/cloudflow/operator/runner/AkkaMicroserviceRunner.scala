/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.operator.runner

import cloudflow.blueprint.deployment._
import cloudflow.operator.CloudflowApplication
import cloudflow.operator.CloudflowLabels
import cloudflow.operator.DeploymentContext
import cloudflow.operator.Name
import cloudflow.operator.Operator
import cloudflow.operator.action._
import play.api.libs.json._
import skuber.Volume._
import skuber._
import skuber.apps.v1.Deployment
import skuber.json.rbac.format._
import skuber.rbac._

/**
 * Creates the Resources that define an Akka Microservice [[Runner]].
 */
object AkkaMicroserviceRunner extends Runner[Deployment] {
  override def format = implicitly[Format[Deployment]]

  override def editor = (obj: Deployment, newMetadata: ObjectMeta) ⇒ {
    obj.copy(metadata = newMetadata)
  }
  override def configEditor = (obj: ConfigMap, newMetadata: ObjectMeta) ⇒ obj.copy(metadata = newMetadata)

  override def resourceDefinition = implicitly[ResourceDefinition[Deployment]]
  override val runtime            = "akka-microservice"

  override def appActions(app: CloudflowApplication.CR, namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference])(
      implicit ctx: DeploymentContext
  ): Seq[Action[ObjectResource]] = {
    val r = role(namespace, labels, ownerReferences)
    Vector(
      Action.createOrUpdate(r, roleEditor),
      Action.createOrUpdate(roleBinding(namespace, r, labels, ownerReferences), roleBindingEditor)
    )
  }

  private def role(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Role =
    Role(
      metadata = ObjectMeta(
        name = Name.ofAkkaMicroserviceRole(),
        namespace = namespace,
        labels = labels(Name.ofAkkaMicroserviceRole()),
        ownerReferences = ownerReferences
      ),
      kind = "Role",
      rules = List(
        createClusterPolicyRule,
        createEventPolicyRule
      )
    )

  private def roleBinding(namespace: String, role: Role, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofAkkaMicroserviceRoleBinding(),
        namespace = namespace,
        labels = labels(Name.ofRoleBinding), // FIXME why not ofAkkaMicroserviceRoleBinding?
        ownerReferences = ownerReferences
      ),
      kind = "RoleBinding",
      roleRef = RoleRef("rbac.authorization.k8s.io", "Role", role.metadata.name),
      subjects = List(
        Subject(
          None,
          "ServiceAccount",
          Name.ofServiceAccount,
          Some(namespace)
        )
      )
    )
  private val createClusterPolicyRule = PolicyRule(
    apiGroups = List(""),
    attributeRestrictions = None,
    nonResourceURLs = List(),
    resourceNames = List(),
    resources = List("pods"),
    verbs = List("get", "list", "watch")
  )

  def resource(
      deployment: StreamletDeployment, // FIXME replace all streamlet specifics
      app: CloudflowApplication.CR,
      configSecret: skuber.Secret,
      namespace: String,
      updateLabels: Map[String, String] = Map()
  )(implicit ctx: DeploymentContext): Deployment = {
    // The runtimeConfig is already applied in the runner config secret, so it can be safely ignored.

    val labels          = CloudflowLabels(app)
    val ownerReferences = List(OwnerReference(app.apiVersion, app.kind, app.metadata.name, app.metadata.uid, Some(true), Some(true)))
    val appId           = app.spec.appId
    val podName         = Name.ofPod(deployment.name)
    val k8sStreamletPorts =
      deployment.endpoint.map(endpoint ⇒ Container.Port(endpoint.containerPort, name = Name.ofContainerPort(endpoint.containerPort))).toList
    val k8sPrometheusMetricsPort = Container.Port(PrometheusConfig.PrometheusJmxExporterPort, name = Name.ofContainerPrometheusExporterPort)
    val akkaManagementPort       = Container.Port(8558, Protocol.TCP, name = "management")
    val akkaHttpPort             = Container.Port(8080, Protocol.TCP, name = "http")

    val podsConfig = getPodsConfig(configSecret)

    val configMapName = Name.ofConfigMap(deployment.name)

    val volume = Volume(configMapName, ConfigMapVolumeSource(configMapName))

    // Streamlet volume mounting
    val streamletToDeploy = app.spec.streamlets.find(streamlet ⇒ streamlet.name == deployment.streamletName)
    val pvcRefVolumes =
      streamletToDeploy.map(_.descriptor.volumeMounts.map(mount ⇒ Volume(mount.name, PersistentVolumeClaimRef(mount.pvcName))).toList)
    val pvcVolumeMounts = streamletToDeploy
      .map(_.descriptor.volumeMounts.map { mount ⇒
        val readOnly = mount.accessMode match {
          case "ReadWriteMany" ⇒ false
          case "ReadOnlyMany"  ⇒ true
        }
        Volume.Mount(mount.name, mount.path, readOnly)
      }.toList)
      .getOrElse(List.empty)

    val secretName   = deployment.secretName
    val secretVolume = Volume(Name.ofVolume(secretName), Volume.Secret(secretName))
    val volumeMount  = Volume.Mount(configMapName, Runner.ConfigMapMountPath, readOnly = true)
    val secretMount  = Volume.Mount(Name.ofVolume(secretName), Runner.SecretMountPath, readOnly = true)

    val configSecretVolumes = getVolumes(podsConfig, PodsConfig.CloudflowPodName)

    val resourceRequirements = createResourceRequirements(podsConfig)
    val environmentVariables = createEnvironmentVariables(app, podsConfig)

    val c = Container(
      name = podName,
      resources = Some(resourceRequirements),
      image = deployment.image,
      env = environmentVariables,
      ports = akkaManagementPort :: akkaHttpPort :: k8sPrometheusMetricsPort :: k8sStreamletPorts,
      volumeMounts = List(secretMount) ++ pvcVolumeMounts ++ getVolumeMounts(podsConfig, PodsConfig.CloudflowPodName) :+ volumeMount :+ Runner.DownwardApiVolumeMount
    )

    val container = c
    //FIXME .withImagePullPolicy(ImagePullPolicy)
      .withLivenessProbe(
        Probe(
          HTTPGetAction(port = "management", path = "/alive"),
          initialDelaySeconds = ProbeInitialDelaySeconds,
          timeoutSeconds = ProbeTimeoutSeconds,
          periodSeconds = Some(ProbePeriodSeconds),
          failureThreshold = Some(ProbeFailureThreshold)
        )
      )
      .withReadinessProbe(
        Probe(
          HTTPGetAction(port = "management", path = "/ready"),
          initialDelaySeconds = ProbeInitialDelaySeconds,
          timeoutSeconds = ProbeTimeoutSeconds,
          periodSeconds = Some(ProbePeriodSeconds),
          failureThreshold = Some(ProbeFailureThreshold)
        )
      )

    // This is the group id of the user in the streamlet container,
    // its need to make volumes managed by certain volume plugins writable.
    // If the image used with the container changes, this value most likely
    // have to be updated
    val dockerContainerGroupId = Runner.DockerContainerGroupId
    // We only need to set this when we want to write to the a volume in a pod
    val fsGroup = pvcVolumeMounts
      .find(volume ⇒ volume.readOnly == true)
      .flatMap(_ ⇒ Some(PodSecurityContext(fsGroup = Some(dockerContainerGroupId))))

    val podSpec =
      Pod
        .Spec(serviceAccountName = Name.ofServiceAccount(),
              volumes = pvcRefVolumes.getOrElse(List.empty[Volume]),
              securityContext = fsGroup)
        .addContainer(container)
        .addVolume(volume)
        .addVolume(secretVolume)
        .addVolume(Runner.DownwardApiVolume)

    val podSpecSecretVolumesAdded = configSecretVolumes.foldLeft[Pod.Spec](podSpec) {
      case (acc, curr) =>
        acc.addVolume(curr)
    }

    val template =
      Pod.Template.Spec
        .named(podName)
        .addLabels(
          labels.withComponent(podName, CloudflowLabels.StreamletComponent) ++ Map(
                Operator.StreamletNameLabel -> deployment.streamletName,
                Operator.AppIdLabel         -> appId
              ).mapValues(Name.ofLabelValue) ++ getLabels(podsConfig, PodsConfig.CloudflowPodName)
        )
        .addAnnotation("prometheus.io/scrape" -> "true")
        .addLabels(updateLabels.updated("app", "shoppingcartservice"))
        .withPodSpec(podSpecSecretVolumesAdded)

    // FIXME hardcoded shoppingcartservice labels

    val deploymentResource = Deployment(
      metadata = ObjectMeta(
        name = podName,
        namespace = namespace,
        labels = labels.withComponent(podName, CloudflowLabels.StreamletComponent).updated("app", "shoppingcartservice"),
        ownerReferences = ownerReferences
      )
    ).withReplicas(deployment.replicas.getOrElse(DefaultReplicas))
      .withTemplate(template)
      .withLabelSelector(
        LabelSelector(LabelSelector.IsEqualRequirement(CloudflowLabels.Name, podName),
                      LabelSelector.IsEqualRequirement("app", "shoppingcartservice"))
      )

    deploymentResource.copy(
      spec = deploymentResource.spec.map(s ⇒ s.copy(strategy = Some(Deployment.Strategy(Deployment.StrategyType.RollingUpdate))))
    )
  }

  def resourceName(deployment: StreamletDeployment): String = Name.ofPod(deployment.name)

  private def createResourceRequirements(podsConfig: PodsConfig)(implicit ctx: DeploymentContext) = {
    var resourceRequirements = Resource.Requirements(
      requests = Map(
        Resource.cpu    -> ctx.akkaMicroserviceRunnerSettings.resourceConstraints.cpuRequests,
        Resource.memory -> ctx.akkaMicroserviceRunnerSettings.resourceConstraints.memoryRequests
      )
    )

    resourceRequirements = ctx.akkaMicroserviceRunnerSettings.resourceConstraints.cpuLimits
      .map { cpuLimit =>
        resourceRequirements.copy(limits = resourceRequirements.limits + (Resource.cpu -> cpuLimit))
      }
      .getOrElse(resourceRequirements)

    resourceRequirements = ctx.akkaMicroserviceRunnerSettings.resourceConstraints.memoryLimits
      .map { memoryLimit =>
        resourceRequirements.copy(limits = resourceRequirements.limits + (Resource.memory -> memoryLimit))
      }
      .getOrElse(resourceRequirements)
    podsConfig.pods
      .get(PodsConfig.CloudflowPodName)
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          resourceRequirements.copy(
            limits = resourceRequirements.limits ++ containerConfig.resources.map(_.limits).getOrElse(Map()),
            requests = resourceRequirements.requests ++ containerConfig.resources.map(_.requests).getOrElse(Map())
          )
        }
      }
      .getOrElse(resourceRequirements)
  }

  private def createEnvironmentVariables(app: CloudflowApplication.CR, podsConfig: PodsConfig)(implicit ctx: DeploymentContext) = {
    val agentPaths = app.spec.agentPaths
    val prometheusEnvVars = if (agentPaths.contains(CloudflowApplication.PrometheusAgentKey)) {
      List(
        EnvVar(PrometheusExporterPortEnvVar, PrometheusConfig.PrometheusJmxExporterPort.toString),
        EnvVar(PrometheusExporterRulesPathEnvVar, PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath))
      )
    } else Nil

    val defaultEnvironmentVariables =
      EnvVar(JavaOptsEnvVar, ctx.akkaMicroserviceRunnerSettings.javaOptions) ::
          // FIXME add to akkaMicroserviceRunnerSettings ?
          EnvVar(BootstrapRequiredContactPoints, "2") ::
          prometheusEnvVars

    val envVarsFomPodConfigMap = podsConfig.pods
      .get(PodsConfig.CloudflowPodName)
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          containerConfig.env
        }
      }
      .toList
      .flatten
      .map { envVar =>
        envVar.name -> envVar
      }
      .toMap

    val defaultEnvironmentVariablesMap = defaultEnvironmentVariables.map { envVar =>
      envVar.name -> envVar
    }.toMap

    (defaultEnvironmentVariablesMap ++ envVarsFomPodConfigMap).values.toList
  }

  val JavaOptsEnvVar                    = "JAVA_OPTS"
  val BootstrapRequiredContactPoints    = "REQUIRED_CONTACT_POINT_NR"
  val PrometheusExporterRulesPathEnvVar = "PROMETHEUS_JMX_AGENT_CONFIG_PATH"
  val PrometheusExporterPortEnvVar      = "PROMETHEUS_JMX_AGENT_PORT"
  val DefaultReplicas                   = 2
  val ImagePullPolicy                   = Container.PullPolicy.Always // FIXME

  val ProbeInitialDelaySeconds = 20
  val ProbeTimeoutSeconds      = 1
  val ProbePeriodSeconds       = 10
  val ProbeFailureThreshold    = 10
}