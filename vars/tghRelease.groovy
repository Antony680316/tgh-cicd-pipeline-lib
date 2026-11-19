// Release identity and audit events.

// Sets GIT_COMMIT, GIT_SHORT, IMAGE_TAG and (main branch only) RELEASE_ID.
def init(steps) {
    def sha = steps.sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    steps.env.GIT_COMMIT = sha
    steps.env.GIT_SHORT = sha.take(12)
    steps.env.IMAGE_TAG = "${steps.env.GIT_SHORT}-b${steps.env.BUILD_NUMBER}"
    if (steps.env.BRANCH_NAME == 'main') {
        def day = new Date().format('yyyyMMdd', TimeZone.getTimeZone('Asia/Taipei'))
        steps.env.RELEASE_ID = "${steps.env.APP_NAME}-${day}-b${steps.env.BUILD_NUMBER}-${steps.env.GIT_SHORT.take(8)}"
        steps.currentBuild.description = "release_id=${steps.env.RELEASE_ID}"
    }
    steps.echo "commit=${sha} image_tag=${steps.env.IMAGE_TAG} release_id=${steps.env.RELEASE_ID ?: '(none: not main)'}"
}

// Sends one structured line to the local syslog (forwarded centrally).
def event(steps, String name, String detail = '') {
    def msg = "release_id=${steps.env.RELEASE_ID ?: '-'} app=${steps.env.APP_NAME} build=${steps.env.BUILD_URL} event=${name} ${detail}"
    steps.withEnv(["TGH_EVT=${msg}"]) {
        steps.sh(script: 'logger -t tgh-cicd -- "$TGH_EVT" || true', label: "audit event ${name}")
    }
}
