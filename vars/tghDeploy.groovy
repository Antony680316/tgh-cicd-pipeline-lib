// Deploys a Docker Compose application by image digest with Ansible.
// The SSH identity used here lives only on the deploy runner
// (~deploy-agent/.ssh/id_ed25519); Jenkins never stores it.

def compose(steps, Map a) {
    if (!(a.imageRef ==~ /[^@\s]+@sha256:[0-9a-f]{64}/)) { steps.error("refusing to deploy non-digest reference: ${a.imageRef}") }
    if (!a.host?.trim()) { steps.error("no target host configured for ${a.stageName}") }
    steps.writeFile file: '.tgh/deploy-compose.yml', text: steps.libraryResource('tgh/ansible/deploy-compose.yml')
    steps.sh 'mkdir -p reports'
    def extra = [
        target_host: a.host, image_ref: a.imageRef, instance: a.instance, app_port: a.port,
        app_env: a.appEnv, app_version: steps.env.IMAGE_TAG, health_path: steps.env.HEALTH_PATH,
        compose_src: "${steps.env.WORKSPACE}/${steps.env.COMPOSE_FILE_REL}",
        release_id: steps.env.RELEASE_ID, record_file: "${steps.env.WORKSPACE}/reports/deploy-${a.stageName}.json"
    ]
    steps.writeFile file: '.tgh/vars.json', text: groovy.json.JsonOutput.toJson(extra)
    steps.withEnv(["TGH_HOST=${a.host}", "ANSIBLE_HOST_KEY_CHECKING=True", "ANSIBLE_FORCE_COLOR=0"]) {
        steps.sh(label: "ansible deploy (${a.stageName})", script: '''
            ansible --version | head -1 > reports/tool-ansible.txt
            rc=0
            ansible-playbook -i "$TGH_HOST," -u "$TGH_DEPLOY_USER" -e @.tgh/vars.json .tgh/deploy-compose.yml \
              > reports/ansible-''' + a.stageName + '''.log 2>&1 || rc=$?
            cat reports/ansible-''' + a.stageName + '''.log
            exit $rc
        ''')
    }
    steps.echo(steps.readFile("reports/deploy-${a.stageName}.json"))
    tghRelease.event(steps, "deployed-${a.stageName}", "host=${a.host} image=${a.imageRef}")
}
