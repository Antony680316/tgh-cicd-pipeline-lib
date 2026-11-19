// Release evidence bundle: collect, describe, hash and ship to the
// append-only evidence store. Shipping failure fails the pipeline, so a
// release can never proceed without its evidence.

def collect(steps, List stashes) {
    steps.sh 'mkdir -p reports'
    stashes.each { name ->
        try { steps.unstash name } catch (ignored) { steps.echo "evidence: stash ${name} not present (stage not reached)" }
    }
}

def writeApproval(steps) {
    def approval = [
        release_id: steps.env.RELEASE_ID, approver: steps.env.APPROVER, approved_at: steps.env.APPROVED_AT,
        approver_group: steps.env.TGH_RELEASE_APPROVERS, image_digest: steps.env.IMAGE_DIGEST,
        test_image: steps.env.TEST_IMAGE_REF, prod_image: steps.env.PROD_IMAGE_REF
    ]
    steps.writeFile file: 'reports/approval.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(approval)) + '\n'
}

def ship(steps, String part) {
    def manifest = [
        schema: 'tgh-release-evidence/v1', part: part,
        release_id: steps.env.RELEASE_ID, app: steps.env.APP_NAME,
        repository: steps.env.GIT_URL ?: steps.scm?.userRemoteConfigs?.getAt(0)?.url,
        branch: steps.env.BRANCH_NAME, commit: steps.env.GIT_COMMIT,
        build_url: steps.env.BUILD_URL, build_number: steps.env.BUILD_NUMBER,
        image_tag: steps.env.IMAGE_TAG, image_digest: steps.env.IMAGE_DIGEST,
        test_image: steps.env.TEST_IMAGE_REF, prod_image: steps.env.PROD_IMAGE_REF,
        approver: steps.env.APPROVER, approved_at: steps.env.APPROVED_AT,
        result: steps.currentBuild.currentResult,
        created_at: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Taipei'))
    ]
    steps.writeFile file: 'reports/manifest.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifest)) + '\n'
    steps.withEnv(["TGH_PART=${part}"]) {
        steps.sh(label: "ship evidence (${part})", script: '''
            set -e
            cd reports
            find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
            cd ..
            tar -C reports -czf evidence.tgz .
            sha256sum evidence.tgz | tee evidence.sha256
            ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -i "$HOME/.ssh/evidence_ed25519" \
              "$TGH_EVIDENCE_USER@$TGH_EVIDENCE_HOST" "ingest $RELEASE_ID $TGH_PART $(cut -d' ' -f1 evidence.sha256)" < evidence.tgz
        ''')
    }
    steps.archiveArtifacts artifacts: 'evidence.tgz,evidence.sha256', allowEmptyArchive: false
    tghRelease.event(steps, "evidence-${part}", "sha256=${steps.readFile('evidence.sha256').trim().split(' ')[0]}")
}
