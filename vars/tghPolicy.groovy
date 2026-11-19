// Vulnerability gate. Rules (architecture section 4, step 5), implemented in
// resources/tgh/scripts/gate.sh:
//   * any CRITICAL finding blocks (exceptions cannot waive it);
//   * a HIGH finding with an available fix blocks unless an unexpired
//     exception exists in resources/tgh/exceptions/<app>.trivyignore.yaml;
//   * HIGH findings without an upstream fix are reported but do not block
//     unless TGH_BLOCK_UNFIXED_HIGH=true (security owner decision);
//   * any secret or HIGH/CRITICAL misconfiguration blocks;
//   * a missing or unreadable report blocks (scan not completed != pass).

def ignoreFile(String app) {
    try {
        return libraryResource("tgh/exceptions/${app}.trivyignore.yaml")
    } catch (ignored) {
        return '{}\n'
    }
}

def gate(steps, String report, String label) {
    steps.writeFile file: '.tgh/gate.sh', text: steps.libraryResource('tgh/scripts/gate.sh')
    steps.sh(label: "policy gate (${label})", script: "bash .tgh/gate.sh '${report}' '${label}'")
}
