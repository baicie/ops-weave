package com.acme.opsweave.platform.inventory;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {EntityController.class, EntityInstanceController.class, EntityTopologyController.class, RelationController.class, ObservationController.class, SourceReviewController.class, AssetIdentityController.class, SourceSnapshotController.class, RejectedWriteAuditController.class, SourceReceiptCapacityController.class})
public class InventoryErrors {
    @ExceptionHandler(com.acme.opsweave.inventory.domain.SourceBindingCorrection.Conflict.class)
    public ResponseEntity<?> correctionConflict(com.acme.opsweave.inventory.domain.SourceBindingCorrection.Conflict failed) { return ResponseEntity.status(409).body(Map.of("error",failed.code().name())); }
    @ExceptionHandler(com.acme.opsweave.inventory.domain.SourceSnapshot.Conflict.class)
    public ResponseEntity<?> snapshotConflict(com.acme.opsweave.inventory.domain.SourceSnapshot.Conflict failed) { return ResponseEntity.status(409).body(Map.of("error",failed.code().name())); }
    @ExceptionHandler(com.acme.opsweave.inventory.api.RelationStore.Conflict.class)
    public ResponseEntity<?> relationConflict(com.acme.opsweave.inventory.api.RelationStore.Conflict failed) { return ResponseEntity.status(409).body(Map.of("error", "RELATION_CONFLICT")); }
    @ExceptionHandler(com.acme.opsweave.inventory.api.RelationStore.Access.class)
    public ResponseEntity<?> relationAccess(com.acme.opsweave.inventory.api.RelationStore.Access failed) { return ResponseEntity.status(403).body(Map.of("error", "FORBIDDEN")); }
    @ExceptionHandler(com.acme.opsweave.inventory.api.RelationStore.NotFound.class)
    public ResponseEntity<?> relationNotFound(com.acme.opsweave.inventory.api.RelationStore.NotFound failed) { return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND")); }
    @ExceptionHandler(com.acme.opsweave.inventory.api.RelationStore.ScanBudgetExceeded.class)
    public ResponseEntity<?> relationScanBudget(com.acme.opsweave.inventory.api.RelationStore.ScanBudgetExceeded failed) { return ResponseEntity.status(503).body(Map.of("error", "RELATION_SCAN_BUDGET_EXHAUSTED")); }
    @ExceptionHandler(com.acme.opsweave.inventory.api.EntityInstanceStore.Access.class)
    public ResponseEntity<?> entityAccess(com.acme.opsweave.inventory.api.EntityInstanceStore.Access failed) { return ResponseEntity.status(403).body(Map.of("error", "FORBIDDEN")); }
    @ExceptionHandler(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class)
    public ResponseEntity<?> entityConflict(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict failed) { return ResponseEntity.status(409).body(Map.of("error", "ENTITY_CONFLICT")); }
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
