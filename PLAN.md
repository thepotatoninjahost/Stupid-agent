# Vorv — what is wrong today, and the order I will fix it in

Written for the owner. No jargon. Each item says what happens on the phone now,
what I change, and how you can tell it worked.

## What the review found

1. The app is real work, not a shell: 98 source files, its own screens, its own
   build pipeline, a bundled Linux system (proot + Debian), a phone builder
   (javac/d8/apksigner), tests that run on every push.
2. The one thing the whole app stands on has never worked on a phone: starting
   the Linux system. Every version up to 0.9.10 failed there. The app could not
   tell you why, because proot's message only named the program it tried.
3. From 0.9.11 the app checks the eight files the Linux system cannot live
   without, unpacks the system again if one is missing, and if proot still
   refuses it says either "this file is missing: …" or "everything is present,
   proot itself refused" — no more guessing.
4. The Linux system is not inside the source code (it is 250 MB and is fetched
   at build time). A build that forgets to fetch it is refused before upload,
   so a computer-less APK can no longer reach your phone.
5. There is no release signing key. That is on purpose, at your instruction,
   until debugging is finished.
6. There is a local-brain path in the code (LocalLlm, ModelManager, CharBrain)
   but it has never been trained, so today the app depends on a cloud key.
7. Two copies of some source files live in the app (the "mirror" the app reads
   about itself). A build step now keeps them identical, so they cannot drift.

## The plan, in the order that removes the most pain first

1. Make the Linux computer start on your phone. It is the foundation of
   everything else. Done when: you tap "Test the Linux system" and it answers
   with its version, on your phone, in one tap.
2. One honest status everywhere. Nothing says done, ready, or green until it has
   actually finished. No green tick before a red report. Done when: the screen's
   claim matches the last real result, including failures.
3. The build tools install: finish or stop. A missing piece stops the install and
   says which piece (already in 0.9.10+); a later tap resumes instead of starting
   over. Done when: install either ends with "installed" or names the piece.
4. The full loop on the phone: describe an app, it writes it, builds it, and
   runs it, and reports the real result of running it. Done when: a small app it
   builds opens on your phone and shows what you asked for.
5. Its own brain you can train. On-phone training of a small model that learns
   your words and your style, with a visible progress line and a "what it
   learned" list. Cloud stays optional, never required. Done when: you teach it
   something, restart the app, and it still knows it.
6. Memory you can see and delete. Everything it remembers about you and your
   projects, in a list, with a delete button per line. Done when: you can read
   and remove what it knows.
7. One screen, plain words. One box to type in, one button, and every error in
   plain words with the next tap spelled out. Done when: no screen has a word
   you have to look up.
8. Hygiene on every version. Tests before upload, a version number you can read
   on the phone, one download link per version that never goes dead. Done when:
   every new version arrives the same way.

## What I need from you

Only this: after each version, install it, tap "Test the Linux system", and send
me the screenshot of what it says. That one line is what tells me which of the
two possible faults it is, and I fix it from there.
