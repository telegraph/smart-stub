def sendNotification(action, token, channel, shellAction) {
    try {
        slackSend message: "${action} Started - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", token: token, channel: channel, teamDomain: "telegraph", baseUrl: "https://hooks.slack.com/services/", color: "warning"
        sh shellAction
    } catch (error) {
        slackSend message: "${action} Failed - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", token: token, channel: channel, teamDomain: "telegraph", baseUrl: "https://hooks.slack.com/services/", color: "danger"
        throw error
    }
    slackSend message: "${action} Finished - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", token: token, channel: channel, teamDomain: "telegraph", baseUrl: "https://hooks.slack.com/services/", color: "good"
}

ansiColor('xterm') {
    lock("${env.PROJECT_NAME}") {
        node("master") {

            def sbtFolder = "${tool name: 'sbt-0.13.13', type: 'org.jvnet.hudson.plugins.SbtPluginBuilder$SbtInstallation'}/bin"
            def projectName = "${env.PROJECT_NAME}"
            def github_token = "${env.GITHUB_TOKEN}"
            def jenkins_github_id = "${env.JENKINS_GITHUB_CREDENTIALS_ID}"
            def github_commit = ""

            stage("Checkout") {
                echo "git checkout"
                checkout changelog: false, poll: false, scm: [
                        $class                           : 'GitSCM',
                        branches                         : [[
                                                                    name: 'master'
                                                            ]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions                       : [
                                [ $class: 'WipeWorkspace' ],
                                [ $class: 'CleanBeforeCheckout' ],
                                [ $class: 'LocalBranch', localBranch: 'master' ]
                        ],
                        submoduleCfg                     : [],
                        userRemoteConfigs                : [[
                                                                    credentialsId: "${jenkins_github_id}",
                                                                    url          : "git@github.com:telegraph/${projectName}.git"
                                                            ]]
                ]
            }

            stage("Build & Unit Tests") {
                sendNotification("Build", "${env.SLACK_PLATFORMS_CI}", "#platforms_ci",
                        """
                  ${sbtFolder}/sbt clean test
                """
                )
                project_version = sh(returnStdout: true, script: """${sbtFolder}/sbt --error 'export version'""").trim()
            }

            stage("Release") {
                sh """
                    ${sbtFolder}/sbt 'release skip-tests with-defaults'
                """
            }

            stage("Release Notes") {
                // Possible error if there is a commit different from the trigger commit
                github_commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                release_version = sh(returnStdout: true, script: """${project_version} | sed  's/-SNAPSHOT//'""").trim()
                //Release on Git
                println("\n[TRACE] **** Releasing to github ${github_token}, ${pipeline_version}, ${github_commit} ****")
                sh """#!/bin/bash
                    GITHUB_COMMIT_MSG=\$(curl -H "Content-Type: application/json" -H "Authorization: token ${
                    github_token
                }" https://api.github.com/repos/telegraph/${projectName}/commits/\"${github_commit}\" | /usr/local/bin/jq \'.commit.message\')
                    echo "GITHUB_COMMIT_MSG: \${GITHUB_COMMIT_MSG}"
                    echo "GITHUB_COMMIT_DONE: DONE"
                    
                    C_DATA="{\\\"tag_name\\\": \\\"v${release_version}\\\",\\\"target_commitish\\\": \\\"master\\\",\\\"name\\\": \\\"${release_version}\\\",\\\"body\\\": \${GITHUB_COMMIT_MSG},\\\"draft\\\": false,\\\"prerelease\\\": false}"
                    echo "C_DATA: \${C_DATA}"
                    curl -H "Content-Type: application/json" -H "Authorization: token ${
                    github_token
                }" -X POST -d "\${C_DATA}" https://api.github.com/repos/telegraph/${projectName}/releases
                """
            }
        }
    }
}
