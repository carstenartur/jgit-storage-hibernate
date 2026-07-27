# Security Policy

## Supported versions

Security fixes are provided for the latest released `0.1.x` version. Development snapshots and older releases are not supported independently; users should upgrade to the newest release before requesting a fix.

| Version | Supported |
|---|---|
| Latest released `0.1.x` | Yes |
| Older `0.1.x` releases | Upgrade required |
| `0.1.x-SNAPSHOT` builds | No security support guarantee |

This policy will be updated when the project introduces another supported release line.

## Reporting a vulnerability

Please do not disclose a suspected vulnerability in a public issue, discussion or pull request.

Use GitHub's **Security** tab and choose **Report a vulnerability** to open a private repository security advisory. Include, where possible:

- the affected artifact and version;
- the database and JGit versions involved;
- a minimal reproduction or proof of concept;
- the expected and observed security impact;
- any known workarounds;
- whether the issue is already public elsewhere.

If private vulnerability reporting is temporarily unavailable, contact the maintainer through the GitHub profile and request a private reporting channel without including vulnerability details in the initial public message.

Reports will be acknowledged as soon as practical. The maintainer will validate the report, determine affected versions, coordinate a fix and publication plan, and credit the reporter unless anonymity is requested. No fixed response or release deadline is guaranteed for this independently maintained project.

## Scope

Security-relevant reports include, but are not limited to:

- unauthorized access across logical repositories;
- corruption or visibility of partially published Git data;
- unsafe schema migration behavior;
- injection through repository, ref, path or search inputs;
- disclosure of credentials or sensitive indexed content;
- supply-chain weaknesses in published artifacts or release automation.

General correctness, performance and compatibility defects may be reported through normal GitHub issues when they do not require confidential handling.
