// Governed pipeline for Docker Compose applications.
//
// Flow (main branch):  CI checks -> image build/scan -> push to test registry
//   -> test deploy by digest -> DAST -> named approval -> promote same digest
//   -> evidence seal -> production deploy by digest -> deployment evidence.
// Pull requests stop after CI checks and the test image build; nothing is
// pushed, deployed or approved from a PR build.
//
// Site specific values come from Jenkins global environment variables
// (TGH_*), never from the application repository.

def call(Map cfg) {
    def appName     = cfg.appName ?: error('appName is required')
    def appPort     = (cfg.appPort ?: 8080) as Integer
    def healthPath  = cfg.healthPath ?: '/health'
    def composeFile = cfg.composeFile ?: 'deploy/compose.yaml'

    pipeline {
        agent none
        options {
            timestamps()
            disableConcurrentBuilds()
            skipDefaultCheckout()
            buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        }
        environment {
            APP_NAME     = "${appName}"
            APP_PORT     = "${appPort}"
            HEALTH_PATH  = "${healthPath}"
            COMPOSE_FILE_REL = "${composeFile}"
        }
        stages {
            stage('CI') {
                agent { label 'docker-build' }
                stages {
                    stage('Checkout') {
                        steps {
                            cleanWs()
                            checkout scm
                            script { tghRelease.init(this) }
                            sh 'mkdir -p reports && git log -1 --format="commit=%H%nauthor=%an <%ae>%ndate=%cI%nsubject=%s" > reports/commit.txt'
                        }
                    }
                    stage('Secret scan') {
                        steps {
                            sh '''
                                gitleaks version > reports/tool-gitleaks.txt
                                gitleaks dir --no-banner --redact --exit-code 1 \
                                  --report-format json --report-path reports/gitleaks.json .
                            '''
                        }
                    }
                    stage('Source scan') {
                        steps {
                            writeFile file: '.tgh-ignore.yaml', text: tghPolicy.ignoreFile(appName)
                            sh '''
                                trivy --version > reports/tool-trivy.txt
                                trivy fs --quiet --scanners vuln,secret,misconfig \
                                  --ignorefile .tgh-ignore.yaml --skip-dirs reports \
                                  --format json -o reports/trivy-fs.json .
                                trivy convert --format table -o reports/trivy-fs.txt reports/trivy-fs.json
                            '''
                            script { tghPolicy.gate(this, 'reports/trivy-fs.json', 'source') }
                        }
                    }
                    stage('Unit tests') {
                        steps {
                            sh '''
                                BASE=$(awk 'toupper($1)=="FROM"{print $2; exit}' Dockerfile)
                                rc=0
                                podman run --rm --network none -v "$PWD":/src:ro,Z -w /src "$BASE" \
                                  python -m unittest discover -s tests -v > reports/unit-tests.txt 2>&1 || rc=$?
                                cat reports/unit-tests.txt
                                exit $rc
                            '''
                        }
                    }
                    stage('Image build') {
                        steps {
                            sh '''
                                podman build --pull=missing --no-cache \
                                  --label org.opencontainers.image.revision=$GIT_COMMIT \
                                  --label org.opencontainers.image.version=$IMAGE_TAG \
                                  -t localhost/$APP_NAME:$IMAGE_TAG .
                                podman save --format oci-archive -o image.tar localhost/$APP_NAME:$IMAGE_TAG
                            '''
                        }
                    }
                    stage('Image scan') {
                        steps {
                            sh '''
                                trivy image --quiet --input image.tar --scanners vuln,secret \
                                  --ignorefile .tgh-ignore.yaml --format json -o reports/trivy-image.json
                                trivy convert --format table -o reports/trivy-image.txt reports/trivy-image.json
                                trivy convert --format cyclonedx -o reports/sbom.cdx.json reports/trivy-image.json
                            '''
                            script { tghPolicy.gate(this, 'reports/trivy-image.json', 'image') }
                        }
                    }
                    stage('Publish to test registry') {
                        when { branch 'main' }
                        steps {
                            withCredentials([usernamePassword(credentialsId: 'harbor-cicd-test',
                                    usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                                sh '''
                                    export REGISTRY_AUTH_FILE="$WORKSPACE_TMP/auth.json"
                                    mkdir -p "$WORKSPACE_TMP"
                                    printf '%s' "$REG_PASS" | podman login --username "$REG_USER" --password-stdin "$TGH_REGISTRY" >/dev/null
                                    podman push --digestfile digest.txt localhost/$APP_NAME:$IMAGE_TAG \
                                      docker://$TGH_REGISTRY/$TGH_TEST_PROJECT/$APP_NAME:$IMAGE_TAG
                                    podman logout "$TGH_REGISTRY" >/dev/null; rm -f "$REGISTRY_AUTH_FILE"
                                '''
                            }
                            script {
                                env.IMAGE_DIGEST = readFile('digest.txt').trim()
                                if (!(env.IMAGE_DIGEST ==~ /sha256:[0-9a-f]{64}/)) { error("bad digest: ${env.IMAGE_DIGEST}") }
                                env.TEST_IMAGE_REF = "${env.TGH_REGISTRY}/${env.TGH_TEST_PROJECT}/${appName}@${env.IMAGE_DIGEST}"
                                writeFile file: 'reports/artifact.txt', text: "test_image=${env.TEST_IMAGE_REF}\ntag=${env.IMAGE_TAG}\n"
                                tghRelease.event(this, 'published', "image=${env.TEST_IMAGE_REF}")
                            }
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: 'reports/**', allowEmptyArchive: true
                        stash name: 'ci-reports', includes: 'reports/**', allowEmpty: true
                        sh 'podman rmi -f localhost/$APP_NAME:$IMAGE_TAG >/dev/null 2>&1 || true; rm -f image.tar'
                    }
                }
            }

            stage('Deploy test') {
                when { branch 'main' }
                agent { label 'deploy' }
                steps {
                    cleanWs()
                    checkout scm
                    script {
                        tghDeploy.compose(this, [
                            stageName: 'test', host: env.TGH_TEST_HOST, imageRef: env.TEST_IMAGE_REF,
                            instance: "${appName}-test", port: appPort, appEnv: 'test'])
                    }
                }
                post { always { stash name: 'deploy-test', includes: 'reports/**', allowEmpty: true } }
            }

            stage('DAST') {
                when { branch 'main' }
                agent { label 'dast' }
                steps {
                    cleanWs()
                    sh '''
                        mkdir -p reports zap && chmod 777 zap
                        TARGET="http://$TGH_TEST_HOST:$APP_PORT/"
                        echo "target=$TARGET image=$TGH_ZAP_IMAGE" > reports/dast-scope.txt
                        set +e
                        docker run --rm --network host -v "$PWD/zap":/zap/wrk:rw "$TGH_ZAP_IMAGE" \
                          zap-baseline.py -t "$TARGET" -m 2 -I -J zap-baseline.json -r zap-baseline.html > reports/zap-baseline.log 2>&1
                        rc=$?
                        set -e
                        cp zap/zap-baseline.* reports/ 2>/dev/null || true
                        tail -5 reports/zap-baseline.log
                        echo "zap_exit=$rc" >> reports/dast-scope.txt
                        # 0 = pass, 2 = warnings only (-I), 1 = FAIL rules, 3 = scanner error
                        [ "$rc" -eq 0 ] || [ "$rc" -eq 2 ] || { echo "DAST gate failed (exit $rc)"; exit 1; }
                    '''
                }
                post { always { stash name: 'dast', includes: 'reports/**', allowEmpty: true } }
            }

            stage('Release approval') {
                when { branch 'main' }
                steps {
                    script {
                        def who
                        timeout(time: 3, unit: 'DAYS') {
                            who = input(message: "核准 ${appName} ${env.IMAGE_TAG} 上正式環境？\n${env.TEST_IMAGE_REF}",
                                        ok: '核准上版', submitter: env.TGH_RELEASE_APPROVERS,
                                        submitterParameter: 'APPROVER')
                        }
                        env.APPROVER = who
                        env.APPROVED_AT = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Taipei'))
                        tghRelease.event(this, 'approved', "approver=${env.APPROVER}")
                    }
                }
            }

            stage('Promote') {
                when { branch 'main' }
                agent { label 'docker-build' }
                steps {
                    cleanWs()
                    withCredentials([
                        usernamePassword(credentialsId: 'harbor-cicd-test', usernameVariable: 'SRC_USER', passwordVariable: 'SRC_PASS'),
                        usernamePassword(credentialsId: 'harbor-cicd-prod', usernameVariable: 'DST_USER', passwordVariable: 'DST_PASS')]) {
                        sh '''
                            mkdir -p reports "$WORKSPACE_TMP"
                            SRC_AUTH="$WORKSPACE_TMP/src.json"; DST_AUTH="$WORKSPACE_TMP/dst.json"
                            printf '%s' "$SRC_PASS" | skopeo login --authfile "$SRC_AUTH" -u "$SRC_USER" --password-stdin "$TGH_REGISTRY" >/dev/null
                            printf '%s' "$DST_PASS" | skopeo login --authfile "$DST_AUTH" -u "$DST_USER" --password-stdin "$TGH_REGISTRY" >/dev/null
                            DST="$TGH_REGISTRY/$TGH_PROD_PROJECT/$APP_NAME"
                            skopeo copy --preserve-digests --src-authfile "$SRC_AUTH" --dest-authfile "$DST_AUTH" \
                              "docker://$TEST_IMAGE_REF" "docker://$DST:$IMAGE_TAG"
                            GOT=$(skopeo inspect --authfile "$DST_AUTH" --format '{{.Digest}}' "docker://$DST:$IMAGE_TAG")
                            rm -f "$SRC_AUTH" "$DST_AUTH"
                            echo "prod_image=$DST@$GOT" > reports/promotion.txt
                            [ "$GOT" = "$IMAGE_DIGEST" ] || { echo "digest mismatch after promotion: $GOT != $IMAGE_DIGEST"; exit 1; }
                        '''
                    }
                    script {
                        env.PROD_IMAGE_REF = "${env.TGH_REGISTRY}/${env.TGH_PROD_PROJECT}/${appName}@${env.IMAGE_DIGEST}"
                        tghRelease.event(this, 'promoted', "image=${env.PROD_IMAGE_REF}")
                    }
                }
                post { always { stash name: 'promote', includes: 'reports/**', allowEmpty: true } }
            }

            stage('Evidence seal') {
                when { branch 'main' }
                agent { label 'deploy' }
                steps {
                    cleanWs()
                    script {
                        tghEvidence.collect(this, ['ci-reports', 'deploy-test', 'dast', 'promote'])
                        tghEvidence.writeApproval(this)
                        tghEvidence.ship(this, 'release')
                    }
                }
            }

            stage('Deploy production') {
                when { branch 'main' }
                agent { label 'deploy' }
                steps {
                    cleanWs()
                    checkout scm
                    script {
                        if (!env.TGH_PROD_HOST?.trim()) { error('TGH_PROD_HOST is not configured; refusing production deploy') }
                        tghDeploy.compose(this, [
                            stageName: 'prod', host: env.TGH_PROD_HOST, imageRef: env.PROD_IMAGE_REF,
                            instance: "${appName}-prod", port: (env.TGH_PROD_PORT ?: "${appPort}") as Integer,
                            appEnv: 'prod'])
                        tghEvidence.ship(this, 'deploy')
                    }
                }
            }
        }
        post {
            failure {
                script {
                    if (env.BRANCH_NAME == 'main' && env.RELEASE_ID) {
                        node('deploy') {
                            cleanWs()
                            tghEvidence.collect(this, ['ci-reports', 'deploy-test', 'dast', 'promote'])
                            tghEvidence.ship(this, 'failed')
                        }
                    }
                }
            }
        }
    }
}
