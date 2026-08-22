# Session Handoff — hex-encoded

Machine-readable handoff for another agent picking up this project.

**Encoding:** plain hex (base16) of a UTF-8 text summary. Reversible — a hash would not be,
since hashes are one-way and cannot be decoded back.

**To decode:**

```bash
xxd -r -p docs/SESSION_HANDOFF.hex
```

or

```bash
python3 -c "print(bytes.fromhex(open('docs/SESSION_HANDOFF.hex').read()).decode())"
```

Payload: 7,177 bytes plaintext / 14,354 hex chars. Covers project scope, the working
agreement, Session 7–8 code state, what is verified vs unverified, known issues,
uncommitted files, and the next-session order.
