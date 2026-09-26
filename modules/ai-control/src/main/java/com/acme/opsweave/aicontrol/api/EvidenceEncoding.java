package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.PlatformEvidence;

/** Exact UTF-8 size of the wire evidence document, provided by a boundary adapter. */
@FunctionalInterface
public interface EvidenceEncoding { int bytes(PlatformEvidence evidence); }
