# Contributing to Rewards_Watcher

Thanks for helping! Anyone can propose changes. Only the maintainer merges
them into `main`, which is protected: changes reach `main` only through
reviewed pull requests.

## How to contribute

1. **Fork** this repository (button at the top right on GitHub).
2. Clone your fork and create a branch:

   ```bash
   git clone https://github.com/<your-username>/Rewards_Watcher.git
   cd Rewards_Watcher
   git checkout -b fix/short-description
   ```

   Branch name ideas: `fix/...`, `feature/...`, `docs/...`.
3. Make your change and test it:

   ```bash
   ./setup.sh
   .venv/bin/python rewards_watcher.py check
   ```

4. Commit and push to **your fork**:

   ```bash
   git push -u origin fix/short-description
   ```

5. Open a **Pull Request** from your branch to `Mohammed-Shajith/Rewards_Watcher:main`.
   Say what you changed and how you tested it.

The maintainer reviews it and, if it's good, merges it. Nothing changes on
`main` until then.

## Guidelines

- Keep it one file and standard library + Playwright where possible.
- Never commit `config.json`, `session.json`, `profile/` or `data/`. They contain your
  sign-in, alert topic and email password. `.gitignore` already excludes
  them; double-check `git status` before committing.
- Don't post screenshots that show your points balance, name, email or ntfy
  topic.
- The watcher must stay read-only: no auto-redeem, no earning points, no
  faster than one check every 2 minutes, and no tricks to hide automation.

## Reporting bugs

Open an issue with your region (e.g. `in`), OS, what you ran, and the output
of `.venv/bin/python rewards_watcher.py check` (remove anything personal).
