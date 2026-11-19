#!/bin/bash
# Vulnerability / secret / misconfiguration gate for a Trivy JSON report.
# usage: gate.sh <report.json> <label>     env: TGH_BLOCK_UNFIXED_HIGH=true|false
# A missing or unreadable report fails: "scan not completed" is never a pass.
set -euo pipefail
report=$1; label=$2; block_unfixed=${TGH_BLOCK_UNFIXED_HIGH:-false}
mkdir -p reports
[ -s "$report" ] || { echo "GATE FAIL ($label): $report missing - scan did not complete"; exit 1; }
jq -e 'has("SchemaVersion")' "$report" >/dev/null || { echo "GATE FAIL ($label): $report unreadable"; exit 1; }

V='[.Results[]?.Vulnerabilities[]?]'
crit=$(jq "$V | map(select(.Severity==\"CRITICAL\")) | length" "$report")
high_fix=$(jq "$V | map(select(.Severity==\"HIGH\" and ((.FixedVersion // \"\") != \"\"))) | length" "$report")
high_nofix=$(jq "$V | map(select(.Severity==\"HIGH\" and ((.FixedVersion // \"\") == \"\"))) | length" "$report")
secrets=$(jq '[.Results[]?.Secrets[]?] | length' "$report")
misconf=$(jq '[.Results[]?.Misconfigurations[]? | select(.Status=="FAIL" and (.Severity=="HIGH" or .Severity=="CRITICAL"))] | length' "$report")

line="gate=$label critical=$crit high_fixable=$high_fix high_unfixed=$high_nofix secrets=$secrets misconfig_high=$misconf block_unfixed_high=$block_unfixed"
echo "$line" | tee -a reports/gate-summary.txt

fail=0
[ "$crit" -eq 0 ] || fail=1
[ "$high_fix" -eq 0 ] || fail=1
[ "$secrets" -eq 0 ] || fail=1
[ "$misconf" -eq 0 ] || fail=1
if [ "$block_unfixed" = true ] && [ "$high_nofix" -ne 0 ]; then fail=1; fi

if [ "$fail" -ne 0 ]; then
  echo "GATE FAIL ($label). Findings:"
  jq -r "$V | map(select(.Severity==\"CRITICAL\" or .Severity==\"HIGH\")) | .[] | \"  \(.Severity) \(.VulnerabilityID) \(.PkgName) \(.InstalledVersion) -> \(.FixedVersion // \"no fix\")\"" "$report" | sort -u | head -50
  jq -r '.Results[]? | .Target as $t | (.Secrets[]? | "  SECRET \($t): \(.RuleID) line \(.StartLine)"), (.Misconfigurations[]? | select(.Status=="FAIL" and (.Severity=="HIGH" or .Severity=="CRITICAL")) | "  MISCONFIG \($t): \(.ID) \(.Title)")' "$report" | head -50
  exit 1
fi
echo "GATE PASS ($label)"
