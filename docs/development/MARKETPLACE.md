# Publishing (maintainer only)

Nothing is published automatically. The signed Marketplace build runs only when started by hand:
GitHub → Actions → CI → Run workflow → tick "verify". It needs these repository secrets (Settings → Secrets and
variables → Actions), which are never stored in the repository:

- `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`: the plugin signing certificate
  (https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

The job builds with `-PriderOnly=true` (the plugin then requires Rider, so the Marketplace offers it to Rider users
only), signs the zip, checks the signature, and runs JetBrains' plugin checker against Rider 2026.1, 2026.2 and 2026.3.
Upload the `torchsharpgraph-marketplace` artifact by hand on the first release.

Name: "Graphs for TorchSharp" (the Marketplace guidelines don't allow other companies' trademarks as a product name;
"for TorchSharp" only says what it works with). If JetBrains asks for a change, use "Live Training Graphs".
