# Git Setup Instructions

Follow these steps to initialize Git and push to a remote repository.

## Step 1: Initialize Git Repository

```bash
cd /Users/mehalchaudhari/Downloads/PaymentIntegrationFramework
git init
```

## Step 2: Add All Files

```bash
# Preview what will be added (optional but recommended)
git status

# Add all files - .gitignore will automatically exclude unnecessary files
git add .
```

**Note:** `git add .` respects `.gitignore` automatically. Files like `.vscode/`, `.cursor/`, `.DS_Store`, `target/`, etc. will NOT be added.

## Step 3: Create Initial Commit

```bash
git commit -m "Initial commit: Payment Integration Framework with Kafka, Redis, and Risk Engine"
```

## Step 4: Create Remote Repository

**Option A: GitHub**
1. Go to https://github.com/new
2. Create a new repository (e.g., `payment-integration-framework`)
3. **Do NOT** initialize with README, .gitignore, or license (we already have these)
4. Copy the repository URL (e.g., `https://github.com/yourusername/payment-integration-framework.git`)

**Option B: GitLab**
1. Go to https://gitlab.com/projects/new
2. Create a new project
3. Copy the repository URL

## Step 5: Add Remote and Push

```bash
# Add remote (replace with your actual repository URL)
git remote add origin https://github.com/yourusername/payment-integration-framework.git

# Or if using SSH:
# git remote add origin git@github.com:yourusername/payment-integration-framework.git

# Push to remote
git branch -M main
git push -u origin main
```

## Step 6: Verify

```bash
# Check remote
git remote -v

# Check status
git status
```

## Troubleshooting

### If you get authentication errors:
- **HTTPS**: Use a Personal Access Token instead of password
  - GitHub: Settings → Developer settings → Personal access tokens → Generate new token
- **SSH**: Set up SSH keys
  - Generate: `ssh-keygen -t ed25519 -C "your_email@example.com"`
  - Add to GitHub/GitLab: Settings → SSH Keys

### If you need to update remote URL:
```bash
git remote set-url origin <new-url>
```

### If you want to check what will be committed:
```bash
git status
git diff --cached
```
