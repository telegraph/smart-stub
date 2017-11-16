import groovy.json.*

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
                sh """git branch --set-upstream-to=origin/master master"""
            }

            stage("Build & Unit Tests") {
                sendNotification("Build", "${env.SLACK_PLATFORMS_CI}", "#platforms_ci",
                        """
                  ${sbtFolder}/sbt clean test
                """
                )
                project_version = sh(returnStdout: true, script: """${sbtFolder}/sbt --error 'export version'""").trim()
                println("\n[TRACE] **** project_version ${project_version} ****")
            }

            stage("Release") {
                sh """
                    ${sbtFolder}/sbt 'release skip-tests with-defaults'
                """
            }

            stage("Release Notes") {
                // Possible error if there is a commit different from the trigger commit
                github_commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                release_version = sh(returnStdout: true, script: """echo ${project_version} | sed  's/-SNAPSHOT//'""").trim()
                current_commit = sh(returnStdout: true, script: 'git show-ref --tags --head --hash| head -n1').trim()
                previous_release_tag = sh(returnStdout: true, script: 'git show-ref --tags --head | sort -V -k2,2 | tail -n2 | head -n1 | cut -d " " -f1').trim()
                release_message = sh(returnStdout: true, script: """git log --format=%B $previous_release_tag..$current_commit""").trim()

                def githubJson = JsonOutput.toJson([tag_name: "v"+ release_version, target_commitish: "master", name: release_version, body: release_message, draft: false, prerelease: false])

                //Release on Git
                println("\n[TRACE] **** Releasing to github ${github_token}, ${release_version}, ${github_commit} ****")
                sh """#!/bin/bash
                    
                    echo "sending to github: ${githubJson}"
                    curl -H "Content-Type: application/json" -H "Authorization: token ${
                    github_token
                }" -X POST -d '${githubJson}' https://api.github.com/repos/telegraph/${projectName}/releases
                """
            }
        }
    }
}
