# AI Agent Instructions for WebSnap

## Communication Preference
- **IMPORTANT**: Although this document is in English, you MUST conduct all conversations, explanations, and comments with the user in **Chinese (zh-CN)**.

## Core Behavioral Rules
1. **No-Build Policy**: 
   - DO NOT execute any build commands (e.g., `./gradlew`, `gradle`, `sdkmanager`).
   - DO NOT attempt to set up a runtime environment or verify the build in your local VM.
   - Reason: Build and verification are handled exclusively via GitHub Actions.
2. **Branching & Pull Requests**:
   - Always work on a new branch created by you. 
   - DO NOT merge code into the `main` or `Anti-Sleep-Workflow` branches directly to avoid polluting the main codebase.

## Coding Style & Consistency
- **Claude Style Preservation**: All existing source code was written by Claude. You must maintain the exact same coding style, patterns, and architectural philosophy.
- **No Simplification**: DO NOT simplify, refactor, or condense code written by Claude unless explicitly instructed. Keep the logic verbose and explicit if that matches the existing style.

## Automated Build Trigger (Crucial)
To ensure your new branch triggers the CI/CD pipeline:
1. **Identify Branch Name**: Note the name of the new branch you have created.
2. **Update Workflow**: Locate the GitHub Actions configuration file (e.g., `.github/workflows/android.yml`).
3. **Inject Trigger**: Add your current branch name to the `on: push: branches:` list in that YAML file.
4. **Unified Commit**: Commit both your code changes and the YAML modification to the new branch before pushing.

## Workflow Goal
- Your task ends when the code is pushed to the new branch with the updated trigger. 
- The user will manually download the APK from GitHub Actions artifacts for real-device testing.
