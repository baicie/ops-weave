package com.acme.opsweave.platform.inventory;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {EntityController.class, ObservationController.class, SourceReviewController.class, AssetIdentityController.class, SourceSnapshotController.class, RejectedWriteAuditController.class})
public class InventoryErrors {
    @ExceptionHandler(com.acme.opsweave.inventory.domain.SourceBindingCorrection.Conflict.class)
    public ResponseEntity<?> correctionConflict(com.acme.opsweave.inventory.domain.SourceBindingCorrection.Conflict failed) { return ResponseEntity.status(409).body(Map.of("error",failed.code().name())); }
    @ExceptionHandler(com.acme.opsweave.inventory.domain.SourceSnapshot.Conflict.class)
    public ResponseEntity<?> snapshotConflict(com.acme.opsweave.inventory.domain.SourceSnapshot.Conflict failed) { return ResponseEntity.status(409).body(Map.of("error",failed.code().name())); }
    @ExceptionHandler(com.acme.opsweave.inventory.domain.SourceScan.Failure.class)
    public ResponseEntity<?> snapshotLease(com.acme.opsweave.inventory.domain.SourceScan.Failure failed) { return ResponseEntity.status(503).body(Map.of("error","SOURCE_SCAN_"+failed.code().name())); }
    @ExceptionHandler(com.acme.opsweave.inventory.domain.SourceReview.Conflict.class)
    public ResponseEntity<?> conflict(RuntimeException failed) { return ResponseEntity.status(409).body(Map.of("error", "SOURCE_REVIEW_CONFLICT")); }
    @ExceptionHandler(com.acme.opsweave.inventory.application.SourceReviewService.Access.class)
    public ResponseEntity<?> denied(com.acme.opsweave.inventory.application.SourceReviewService.Access failed) {
        return ResponseEntity.status(failed.status()).body(Map.of("error", failed.status() == 503 ? "CMDB_IMPORT_NOT_CONFIGURED" : failed.status() == 403 ? "FORBIDDEN" : "NOT_FOUND"));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception failed) { return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST")); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unavailable(IllegalStateException failed) { return ResponseEntity.status(503).body(Map.of("error", "INVENTORY_READ_UNAVAILABLE")); }
}
