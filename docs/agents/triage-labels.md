# Triage labels

The skills speak in terms of five triage roles.
This file maps those roles to the strings used in this repository.

There are no tracker labels, because there is no remote tracker.
**A role is written as the `Status:` line near the top of the ticket file.**
See `issue-tracker.md`.

| Label in mattpocock/skills | Label in this tracker | Meaning |
| --- | --- | --- |
| `needs-triage` | `needs-triage` | A maintainer needs to evaluate this ticket |
| `needs-info` | `needs-info` | Waiting on the reporter for more information |
| `ready-for-agent` | `ready-for-agent` | Fully specified, ready for an agent working alone |
| `ready-for-human` | `ready-for-human` | Needs a human to implement it |
| `wontfix` | `wontfix` | Will not be done |

## Statuses this project adds

These are not triage roles.
They describe how far a ticket got.

| Status | Meaning |
| --- | --- |
| `claimed` | A session is working this ticket right now. Set it before starting. |
| `resolved` | Finished. |
| `needs-device` | The code is complete, but some criteria need the real television. List which ones. |
| `needs-decision` | The code is complete, but finishing it needs a decision only the project owner can make. Say what the decision is. |
| `deferred` | Decided against for now. It unblocks its dependants and is never offered as work. Carries the date and the reason. |

A `Status:` line may carry a short qualifier in brackets, for example `needs-device (5 of 8 criteria ticked on the television)`.

## Triage is only for tickets you did not create

Tickets produced by a planning skill such as `/to-tickets` are already ready for an agent.
Do not triage them.
