# K3s's Kubernetes API listens on 6443, but it is intentionally absent from
# this allowlist. Operators use the managed SSH alias instead.
resource "hcloud_firewall" "k3s" {
  name = "k3s-fixture-k3s"

  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["0.0.0.0/0"]
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = ["0.0.0.0/0"]
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "80"
    source_ips = ["0.0.0.0/0"]
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "443"
    source_ips = ["0.0.0.0/0"]
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "hcloud_firewall_attachment" "k3s" {
  firewall_id = hcloud_firewall.k3s.id
  server_ids  = [hcloud_server.node1.id]
}
