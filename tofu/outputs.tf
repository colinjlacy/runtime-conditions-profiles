output "k3s_server_ip" {
  description = "IP address of the K3s server node"
  value       = module.k3s_server.vm_ip
}

output "k3s_server_url" {
  description = "K3s server URL for joining workers"
  value       = module.k3s_server.server_url
}

output "k3s_token" {
  description = "K3s cluster token"
  value       = random_password.k3s_token.result
  sensitive   = true
}

output "ubuntu_password" {
  description = "Password for ubuntu user on all VMs (for console access)"
  value       = random_password.ubuntu_password.result
  sensitive   = true
}

output "worker_ips" {
  description = "IP addresses of worker nodes"
  value       = [for w in module.k3s_workers : w.vm_ip]
}

output "cilium_install_command" {
  description = "Instructions for installing Cilium as the CNI and LoadBalancer controller"
  value       = <<-EOT
    Cilium is NOT installed automatically. K3s was bootstrapped with flannel,
    the built-in network policy controller, ServiceLB, and Traefik disabled, so
    the cluster has no CNI until Cilium is installed. Nodes will report NotReady
    until then.

    Point kubectl/helm at the cluster (the kubeconfig lives on the server at
    /etc/rancher/k3s/k3s.yaml; replace 127.0.0.1 with ${module.k3s_server.vm_ip}),
    then install Cilium with Helm:

      helm repo add cilium https://helm.cilium.io/
      helm repo update

      helm install cilium cilium/cilium \
        --namespace kube-system \
        --set k8sServiceHost=${module.k3s_server.vm_ip} \
        --set k8sServicePort=6443 \
        --set operator.replicas=1 \
        --set l2announcements.enabled=true \
        --set externalIPs.enabled=true

    Setting k8sServiceHost/k8sServicePort lets the Cilium agents reach the API
    server directly while no CNI is present (avoiding a chicken-and-egg startup).

    Verify the install:

      cilium status --wait
      kubectl get nodes

    To replace ServiceLB, apply a CiliumLoadBalancerIPPool and a
    CiliumL2AnnouncementPolicy after Cilium is healthy so that Services of type
    LoadBalancer receive external IPs from your LAN range.
  EOT
}

