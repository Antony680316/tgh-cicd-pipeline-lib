// Vulnerability gate.
//
// Blocking rules (architecture section 4, step 5):
//   * any CRITICAL finding blocks (fixed or not, exceptions cannot waive it);
//   * a HIGH finding with an available fix blocks unless an unexpired
//     exception exists in exceptions/<app>.trivyignore.yaml;
//   * HIGH findings with no upstream fix are reported in the evidence but do
//     not block (policy decision recorded in the manual; security owner may
//     tighten this with TGH_BLOCK_UNFIXED_HIGH=true);
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
    def blockUnfixed = (steps.env.TGH_BLOCK_UNFIXED_HIGH ?: 'false') == 'true'
    steps.withEnv(["TGH_REPORT=${report}", "TGH_LABEL=${label}", "TGH_BU=${blockUnfixed}"]) {
        steps.sh(label: "policy gate (${label})", script: '''
            set -e
            [ -s "$TGH_REPORT" ] || { echo "GATE FAIL: $TGH_REPORT missing - scan did not complete"; exit 1; }
            jq -e '.Results' "$TGH_REPORT" >/dev/null 2>&1 || jq -e '.SchemaVersion' "$TGH_REPORT" >/dev/null \
              || { echo "GATE FAIL: $TGH_REPORT unreadable"; exit 1; }
            V='[.Results[]?.Vulnerabilities[]?]'
            crit=$(jq "$V | map(select(.Severity==\\"CRITICAL\\")) | length" "$TGH_REPORT")
            high_fix=$(jq "$V | map(select(.Severity==\\"HIGH\\" and ((.FixedVersion // \\"\\") != \\"\\"))) | length" "$TGH_REPORT")
            high_nofix=$(jq "$V | map(select(.Severity==\\"HIGH\\" and ((.FixedVersion // \\"\\") == \\"\\"))) | length" "$TGH_REPORT")
            secrets=$(jq '[.Results[]?.Secrets[]?] | length' "$TGH_REPORT")
            misconf=$(jq '[.Results[]?.Misconfigurations[]? | select(.Status=="FAIL" and (.Severity=="HIGH" or .Severity=="CRITICAL"))] | length' "$TGH_REPORT")
            line="gate=$TGH_LABEL critical=$crit high_fixable=$high_fix high_unfixed=$high_nofix secrets=$secrets misconfig_high=$misconf block_unfixed_high=$TGH_BU"
            echo "$line" | tee -a reports/gate-summary.txt
            fail=0
            [ "$crit" -eq 0 ] || fail=1
            [ "$high_fix" -eq 0 ] || fail=1
            [ "$secrets" -eq 0 ] || fail=1
            [ "$misconf" -eq 0 ] || fail=1
            if [ "$TGH_BU" = true ] && [ "$high_nofix" -ne 0 ]; then fail=1; fi
            if [ $fail -ne 0 ]; then
              echo "GATE FAIL ($TGH_LABEL). Findings:"
              jq -r "$V | map(select(.Severity==\\"CRITICAL\\" or .Severity==\\"HIGH\\")) | .[] | \\"  \(.Severity) \(.VulnerabilityID) \(.PkgName) \(.InstalledVersion) -> \(.FixedVersion // \\"no fix\\")\\"" "$TGH_REPORT" | sort -u | head -50
              jq -r '.Results[]? | .Target as $t | (.Secrets[]? | "  SECRET \($t): \(.RuleID) line \(.StartLine)"), (.Misconfigurations[]? | select(.Status=="FAIL" and (.Severity=="HIGH" or .Severity=="CRITICAL")) | "  MISCONFIG \($t): \(.ID) \(.Title)")' "$TGH_REPORT" | head -50
              exit 1
            fi
            echo "GATE PASS ($TGH_LABEL)"
        ''')
    }
}
