# Agent Ground Rules

## Commit & PR Workflow
- **Auto-commit**: Commit all changes automatically after completing work (do not ask for confirmation).
- **Conventional Commits**: Use the [Conventional Commits](https://www.conventionalcommits.org/) specification for all commit messages (e.g., `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `test:`).
- **Push & PR**: Push changes to a feature branch and create a PR against `main` for review.
- **Branch cleanup**: After a PR is merged, delete the local (and remote) feature branch.

## Prompt Feedback
- After completing a task, provide brief feedback on the user's prompt — suggest improvements for clarity, specificity, or structure that would lead to better results.

## Cost Optimization
- Propose using subagents with lighter models (e.g., Haiku for simple searches, Sonnet for moderate tasks) when it can reduce cost without sacrificing quality. Reserve Opus for complex reasoning or critical decisions.

## Markdown Style
- Use **Mermaid diagrams** (```` ```mermaid ````) in all `.md` files where a visual would aid understanding — architecture flows, data pipelines, decision trees, hierarchies, sequences, etc.
- Prefer Mermaid over ASCII art for any diagram that involves more than a simple linear flow.
