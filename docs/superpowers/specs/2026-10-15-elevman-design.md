# ElevMan: Elevator Documentation and Maintenance App (Design)

Date: 2026-10-15
Status: Draft for review

## 1. Purpose

An Android app for an elevator setup and repair technician. It holds his manufacturer manuals, the error codes they list, and the maintenance tasks and history for each elevator he services. He must be able to find the right manual, look up a fault code, and see what maintenance is due, including in machine rooms and shafts with no signal.

## 2. Users and sharing

- The technician and a few colleagues. There are no user accounts and no server.
- Each phone holds a full copy of the library.
- Phones share data by exchanging backup files. Changes are not live-synced.

## 3. Scope

**In v1**
- Import manufacturer PDF manuals.
- Extract suggested error codes and maintenance tasks from PDFs on the phone, with human review.
- Search error codes across models.
- Track elevators, their maintenance due dates, and a log of completed work.
- Export and import a backup zip containing the database and all PDFs.

**Out of scope for v1**
- iPhone or web versions.
- User accounts and live cloud sync.
- Photos, client sign-off, push reminders, and multiple languages.

## 4. Approach

Native Android with Kotlin and Jetpack Compose. Records are stored in a local SQLite database through Room. PDFs are stored in app-private storage. PDF text is read on the device with an on-device library, chosen during planning (candidates: PdfBox-Android or PDFium). Backup and import use the Android file picker and share sheet.

Everything works offline. No PDF or record leaves the phone.

## 5. Components

Each unit has one job and talks to the others through the data layer.

1. **Library.** Manufacturers, models, and their manuals. Imports PDFs and detects duplicates by file hash.
2. **Extraction.** Reads text from a PDF and proposes error codes and maintenance tasks, each tied to a page number. Writes them with status *suggested*. Does not decide what is confirmed.
3. **Review.** Shows each suggestion beside its source page. The user accepts, edits or rejects it. Sets status to *confirmed* or *rejected*.
4. **Elevators and maintenance.** Elevators, their tasks and due dates, and the log. Marking a task done writes a log entry and recalculates the next due date.
5. **Search.** Finds error codes across models. Confirmed codes rank above suggested ones, and suggested codes are shown with a label.
6. **Backup.** Exports and imports the zip. Import is previewed and all-or-nothing.
7. **PDF viewer.** Opens a manual offline at a given page, with search inside the document.

## 6. Data model

Every record has a UUID and an `updatedAt` timestamp.

- **Manufacturer**: name.
- **ElevatorModel**: manufacturer, name, notes.
- **Manual**: model, title, PDF file path, file hash, page count.
- **ErrorCode**: model, source manual, code (text; codes such as "E-21" occur), meaning, cause and fix, page number, status (*suggested* / *confirmed* / *rejected*).
- **MaintenanceTask**: model, name, interval in months, source manual, page number, status (same values).
- **Elevator**: site, customer, model, serial number, notes.
- **ElevatorTask**: elevator, task, next due date. Next due date is calculated from the most recent completed log entry plus the interval.
- **LogEntry**: elevator, task (optional), date, note.

## 7. Key flows

1. **Import a manual.** Pick the PDF. Copy it into app storage. Compute its hash and skip it if it is a duplicate. Extract text. Save the results as *suggested* items.
2. **Review.** Accept, edit or reject each suggestion beside its page. Only *confirmed* items are shown as valid on site. The review screen has an **Accept all** button. It shows how many suggestions it will confirm and requires a second tap before applying.
3. **Field lookup.** Type a code. Results show every model that has it, with the manual and page.
4. **Mark a task done.** Write a log entry and move the elevator task's next due date forward by the interval.
5. **Export.** Write one zip: the database as JSON plus a folder of PDFs. Share it through the share sheet.
6. **Import.** Read the zip and validate it. Show a preview of what will be added, updated or conflicting. Apply everything in one transaction, or change nothing if the zip is corrupt.
7. **Merge rule.** Records are matched by UUID. For the same UUID, the record with the newer `updatedAt` wins. Records that exist on only one side are added.

## 8. Screens

Four bottom tabs:

1. **Search (home).** A large search box at the top. Results grouped by model, confirmed codes first. Tapping a result opens the manual at the page.
2. **Library.** Manufacturers, then models, then each model's manuals, confirmed codes and maintenance tasks. An Import button. Models with suggestions waiting show a count.
3. **Elevators.** Grouped by site. Each elevator shows its model, serial number, tasks with due dates (overdue first), and log history. "Mark done" and "Add note" actions.
4. **Backup.** Export, and import with preview.

Screens outside the tabs: **Review** (suggestions beside source pages, with Accept, Edit, Reject and Accept all) and **PDF viewer**.

Design for field use: large tap targets, high-contrast text readable in bright light and dim machine rooms, and no nested menus for common actions.

## 9. Error handling

- **Scanned PDF with no extractable text.** Zero suggestions, with a message that codes must be entered by hand. Manual entry is always available.
- **Duplicate PDF.** Skipped, with a message naming the existing manual.
- **Corrupt or incompatible backup.** Import is rejected with no changes made.
- **Insufficient storage during import.** Transaction rolls back; no partial import.
- **Very large backup.** The export screen shows the size before sharing.

## 10. Testing

- **Unit tests:** merge rules, due-date calculation, duplicate detection by hash, status transitions.
- **Extraction tests:** a fixed set of sample manuals, both text-based and scanned, with expected suggestions for the text-based ones and expected empty results for the scanned ones.
- **Integration tests:** import, review and confirm a manual; export; import on a clean database; verify the result matches.
- **Field test:** run a full day's use in airplane mode before release.

## 11. Known risks and limits

- **Extraction accuracy.** Text extraction from PDF tables can lose row structure, so a code may be paired with the wrong meaning. Every suggestion is reviewed before it is treated as valid, and the review screen shows the source page for this reason.
- **Accept all.** It makes bulk approval one step faster. The confirm step and the visible count are the safeguards. This is an accepted trade-off.
- **Scanned PDFs.** These need manual entry in v1.
- **Backup size.** Backups with PDFs can reach hundreds of MB, so transfer through messaging apps may fail. Email and Google Drive are the expected routes.
- **Concurrent edits.** Two phones editing the same record between syncs resolve by newer `updatedAt`. The losing edit is listed in the import preview so it is not silently discarded.
- **Maintenance tasks.** Assumed to be extracted from manuals the same way as error codes, with the same review.

## 12. Assumptions to verify

- Manuals are mostly text-based PDFs. Test with real samples before committing to the extraction library.
- The team's phones are Android 8.0 or later (the app's minimum SDK is 26).
- Manual volume is dozens to a few hundred PDFs.
