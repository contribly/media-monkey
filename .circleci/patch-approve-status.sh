#!/bin/bash

set -e

# Note: the APPROVE_JOB_NAME should also include the workflow name
REPO_PATH="$CIRCLE_PROJECT_USERNAME/$CIRCLE_PROJECT_REPONAME"
# Note: you should also set a valid GitHub token in the GITHUB_STATUS_UPDATE_TOKEN variable

echo "Patching approval job named: $APPROVE_JOB_NAME"

for i in {1..10}
do
  echo "waiting for status to appear..."

  sleep 10

  curl --request GET \
    --url "https://api.github.com/repos/$REPO_PATH/statuses/$CIRCLE_SHA1" \
    --header 'Accept: application/vnd.github.v3+json' \
    --header "Authorization: Bearer $GITHUB_STATUS_UPDATE_TOKEN" > commit-statuses.json

  cat commit-statuses.json | jq -r '.[].context' > commit-statuses.txt

  if grep -q "ci/circleci: $APPROVE_JOB_NAME" "commit-statuses.txt"; then
    echo "status appeared, patching the pending state"
    URL=$(cat commit-statuses.json| jq -r --arg name "$APPROVE_JOB_NAME" -c 'map(select(.context | contains($name))) | .[].target_url' | head -1)

    curl --request POST \
      --url "https://api.github.com/repos/$REPO_PATH/statuses/$CIRCLE_SHA1" \
      --header 'Accept: application/vnd.github.v3+json' \
      --header "Authorization: Bearer $GITHUB_STATUS_UPDATE_TOKEN" \
      --header 'Content-Type: application/json' \
      --data '{
        "state": "success",
        "target_url": "'"$URL"'",
        "description": "Patched pending state, please visit circleCI to start the approval.",
        "context": "ci/circleci: '"$APPROVE_JOB_NAME"'"
      }'

    exit 0
  fi
done

echo "Could not patch CircleCI approval, timed out"
