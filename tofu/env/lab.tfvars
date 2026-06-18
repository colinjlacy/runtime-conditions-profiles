# Proxmox Configuration
proxmox_endpoint    = "https://192.168.0.101:8006"
proxmox_username    = "root@pam"
# proxmox_password    = "<your-password-here>"
proxmox_insecure    = true
proxmox_node        = "nuc"


# Storage and Network
vm_storage          = "local-lvm"
vm_network_bridge   = "vmbr0"
vm_disk_size_gb     = 100
gpu_vm_disk_size_gb = 120

# Cluster Configuration
name_prefix         = "k3s"
k3s_version         = "v1.35.0+k3s1"

# SSH Access
# Fetch your SSH key from GitHub: https://github.com/colinjlacy.keys
ssh_public_keys = [
  "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDAaFE4fOVH6nMhLiL7SlRWWdKl5I/HTyQW6ZiMCJVg9PU8eZgE/8htljzDSm7B+reFTtseBnFU5Dh5FE6rZ6nQYp6ZZ6vb9Fn+eU49jaQHTX8HHq04uYLw9TL2QKRty8UXx3Nx1k083IvKD9oNC1ms9Z7DGRo6G5GtNtcO4bmoy14KSgK0mKwkJVBhfN+KYJktWs03Ed6xh84acw1mzuXOtJR/VoGzcPd/rRWGnT/hISVbu6S6kTKjTqtgOedAaGrxnzShT5pG7VM1Qgj7o/4oTzcmLzKtCiGsF+6TQBzn/ca9dwOq0PCZuJgNZ6qGfclq184g3l6JMDKNFl4P2zrvcLkBn6AswG9aZa1nJ5JSP5QHlpnwSPZti2ZSjO+/MGzw0cVsUNF5l5wYi934z5srU4NwOvYdOcWeC9dkuWt6xi47plNdc93p77J5d+r1kWIksSQi4F6fRN8Wtb0mOSBS/q7l2h6m2PCM3HFWrN93V8mO9fd3Qdl8nd0EsyTLTkyvkVKtv5rcbXCf2mDcTpFke1NWL+Kt4nl/JwtRP99XJAuQsHHL0mJLxbcOJK0nyiNc94BJD1CU17NIPLxG9am1RDztiYjN+EOI/PShWJ/Xnff4pNUNT3WbpNSL5KXav/hZk3FlVDPIPb0sypmkIXg91svDtdauL6WeLDtysANwTQ==",
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINnE3VTRqLkC6MYqdcrfB668Zlm0K0J4p6g6TOWrZGwk"
]

# Node Sizing
server_cores        = 4
server_memory_mb    = 4096

worker_cores        = 4
worker_memory_mb    = 8192
worker_count        = 3

