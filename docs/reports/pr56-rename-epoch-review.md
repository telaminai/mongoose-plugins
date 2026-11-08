# PR 56: replay-guard rename review

Reviewed `0853b894296abf24825d613417c6ef8c41e1f2df`, based on main
`7f39a8d7d587a00ca4a7c8b87f7c5ffedbddfbe6`, against Mongoose 1.1.3.
Fixes are offered separately on `review/pr56-fixes`; the PR author accepts or rejects them.

## Findings and fixes

### F1 — P1: epoch equality is not cross-feed ownership

At the reviewed head, a saved `original` entry with epoch 7 and position 100 is taken by a newly added
`additional` feed before `original` has delivered in the new process. The new feed's position 100 is then skipped,
even though that logical feed never applied it. Observing names already delivered does not establish which feeds
are still configured or prove that the old name was retired.

This is reachable with real sources: two FileEventSources on one file load the same pointer epoch. In Mongoose
1.1.3, `FileEventSource` also uses file identity for non-committed strategies; `EventToQueuePublisher.setSourceEpoch`
is public and imposes no uniqueness requirement. A copied pointer can retain its epoch, subject to the source's
file-identity checks. Epochs are position-space identifiers, not declarations of a feed rename.

The fix requires a node to declare the retired predecessor through `renamedFrom(String feed)`; its default is null.
Only that predecessor can transfer its matching non-zero epoch/position. Undeclared names stay independent.
An observed active predecessor is refused before take-over. README and Javadoc describe the contract and limits.

**Author decision:** this deliberately replaces automatic inference with explicit rename intent. It is the ownership
contract offered for review, not a claim that matching epochs prove identical inputs. A declaration must never
describe an additional subscription or a different logical input, and the old feed must be stopped before restart.

Wrong-result witnesses:

- `PersistentNodeTest#a_new_feed_with_a_shared_epoch_is_not_assumed_to_be_a_rename` fails at its applied-event
  assertion on the reviewed PR head.
- `PersistentNodeTest#real_file_pointer_epochs_require_explicit_rename_consent` establishes shared epochs with actual
  FileEventSources and exercises both independent and explicitly renamed feeds through the real DeliveryContext.
  Restoring epoch-only inference fails its independent-feed assertion as well as the first witness (two assertion
  failures, no test errors).

### F2 — P2: a historical target name masks the renamed input's position

With saved positions `old=(7,100)` and `new=(8,100)`, renaming `old` to `new` while retaining epoch 7 is not recognised:
the lookup returns the historical epoch-8 entry before considering the matching predecessor. Position 100 is applied
again although its effect was already saved under `old`.

The fix treats an existing entry as authoritative for this delivery only when its epoch matches. Otherwise the
explicit predecessor can supply the position. `PersistentNodeTest#a_rename_into_a_historical_name_uses_the_matching_epoch`
fails at its replay assertion on the reviewed head. Restoring the old early return in the fixed code also produces
one named assertion failure, with no test errors.

## Verification

- Original cache-module suite: 82 total / 0 failures / 0 errors / 1 skip.
- Final cache-module suite on JDK 21: 90 total / 0 failures / 0 errors / 1 skip, using
  `./mvnw -o -pl service/svc-cache test` with an empty settings file.
- The author's two rename tests were run with main's PersistentNode implementation: both failed at their replay
  assertions, with no test errors. The reviewed PR source was then restored from its byte copy and its SHA-256 checked.
- Each fix control was followed by a green restored run of PersistentNodeTest and PersistentNodeCompletionTest:
  36 total / 0 failures / 0 errors / 1 skip. Source and both PersistentNode class files matched their pre-control bytes.
- Additional coverage includes declared rename plus reset/zero epochs, an explicitly selected predecessor among
  shared epochs, an active predecessor, retry rollback to the old-name baseline, and independent nodes' completion
  callbacks. The existing no-hook rename witness remains covered.
- The one skip is `guard_is_off_without_a_delivery_context`: this build has the real DeliveryContext. The real-context
  guard and new real-file-epoch witness execute; they are not skipped.

The real-source witness starts/stops source objects over disposable files and manually enters the actual delivery
context. It does not start a server, kill a process mid-delivery, or claim an end-to-end crash test. No cross-node
transaction guarantee was added: the existing single-node/independent-node limitation still applies. Handwritten
processor retries remain unsupported and warned; the added retry test checks the existing best-effort rollback.

Local failed attempts and control logs were retained outside the repository. The first real-source test attempt
omitted its EventFlowManager wiring and failed with a harness setup error; that setup was corrected before recording
the green run or interpreting any wrong-result witness. CI results must be read at the pushed head; the existing
MavenCI workflow uses `-DskipTests`, so a green build there does not establish test execution.
