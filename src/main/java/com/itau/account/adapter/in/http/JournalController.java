package com.itau.account.adapter.in.http;

import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.port.in.RequestJournalReplayCommand;
import com.itau.account.application.port.in.TraceJournalQuery;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Journal HTTP surface. Access is deny-by-default via {@link com.itau.account.application.port.out.JournalAccessPolicy}
 * (no client-spoofable role headers). Every attempt is audited before the decision is enforced.
 */
@RestController
@RequestMapping("/internal/journal")
public class JournalController {

    static final String ANONYMOUS_SUBJECT = "anonymous";

    private final TraceJournalQuery traceJournalQuery;
    private final RequestJournalReplayCommand requestJournalReplayCommand;

    public JournalController(
            TraceJournalQuery traceJournalQuery,
            RequestJournalReplayCommand requestJournalReplayCommand
    ) {
        this.traceJournalQuery = traceJournalQuery;
        this.requestJournalReplayCommand = requestJournalReplayCommand;
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<List<JournalRecord>> byTransaction(@PathVariable String transactionId) {
        return ResponseEntity.ok(
                traceJournalQuery.byTransaction(ANONYMOUS_SUBJECT, TransactionId.parse(transactionId)));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<List<JournalRecord>> byAccount(@PathVariable String accountId) {
        return ResponseEntity.ok(
                traceJournalQuery.byAccount(ANONYMOUS_SUBJECT, AccountId.parse(accountId)));
    }

    @PostMapping("/replay")
    public ResponseEntity<Void> replay() {
        requestJournalReplayCommand.requestReplay(ANONYMOUS_SUBJECT);
        return ResponseEntity.accepted().build();
    }
}
