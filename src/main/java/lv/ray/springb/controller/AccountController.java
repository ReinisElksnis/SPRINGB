package lv.ray.springb.controller;

import lv.ray.springb.constants.ApiConstants;
import lv.ray.springb.constants.ApiConstants.Endpoints;
import lv.ray.springb.constants.ApiConstants.ResponseKeys;
import lv.ray.springb.constants.ApiConstants.SubPaths;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.CreateAccountRequest;
import lv.ray.springb.dto.MoneyRequest;
import lv.ray.springb.dto.OperationDTO;
import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.dto.TransferRequest;
import lv.ray.springb.dto.TransferResultDTO;
import lv.ray.springb.service.AccountException;
import lv.ray.springb.service.AccountService;
import lv.ray.springb.service.LedgerReconciliationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


/**
 * Every endpoint here requires an authenticated session (see {@code SecurityConfig}) and always
 * acts as the signed-in caller - there is no "act on behalf of another user" path, so an account
 * id in a URL can only ever resolve to one of the caller's own accounts.
 */
@RestController
@RequestMapping(Endpoints.ACCOUNTS)
@CrossOrigin(origins = ApiConstants.ALL_ORIGINS)
public class AccountController
{

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final AccountService accountService;

	private final LedgerReconciliationService reconciliationService;

	public AccountController(final AccountService accountService,
			final LedgerReconciliationService reconciliationService)
	{
		this.accountService = accountService;
		this.reconciliationService = reconciliationService;
	}

	@PostMapping
	public ResponseEntity<AccountDTO> createAccount(@RequestBody final CreateAccountRequest request,
			final Authentication authentication)
	{
		final AccountDTO account = accountService.createAccount(authentication.getName(), request.currency());
		return ResponseEntity.status(HttpStatus.CREATED).body(account);
	}

	@GetMapping
	public List<AccountDTO> getMyAccounts(final Authentication authentication)
	{
		return accountService.getAccountsForOwner(authentication.getName());
	}

	@GetMapping(SubPaths.BY_ID)
	public AccountDTO getAccount(@PathVariable final Long id, final Authentication authentication)
	{
		return accountService.getAccount(id, authentication.getName());
	}

	@GetMapping(SubPaths.BY_ID + SubPaths.OPERATIONS)
	public List<OperationDTO> getOperations(@PathVariable final Long id, final Authentication authentication)
	{
		return accountService.getOperations(id, authentication.getName());
	}

	/**
	 * Ownership is enforced the same way every other account endpoint enforces it: {@code
	 * getAccount} throws (404, whether the id is unknown or simply belongs to someone else) before
	 * the reconciliation service ever sees the id, so this can't be used to probe another
	 * caller's ledger.
	 */
	@GetMapping(SubPaths.BY_ID + SubPaths.RECONCILE)
	public ReconciliationResult reconcile(@PathVariable final Long id, final Authentication authentication)
	{
		accountService.getAccount(id, authentication.getName());
		return reconciliationService.reconcileAccount(id);
	}

	@PostMapping(SubPaths.BY_ID + SubPaths.DEPOSITS)
	public OperationDTO deposit(@PathVariable final Long id,
			@RequestBody final MoneyRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) final String idempotencyKey,
			final Authentication authentication)
	{
		return accountService.deposit(id, authentication.getName(), request.amount(), idempotencyKey);
	}

	@PostMapping(SubPaths.BY_ID + SubPaths.WITHDRAWALS)
	public OperationDTO withdraw(@PathVariable final Long id,
			@RequestBody final MoneyRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) final String idempotencyKey,
			final Authentication authentication)
	{
		return accountService.withdraw(id, authentication.getName(), request.amount(), idempotencyKey);
	}

	@PostMapping(SubPaths.TRANSFERS)
	public TransferResultDTO transfer(@RequestBody final TransferRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) final String idempotencyKey,
			final Authentication authentication)
	{
		return accountService.transfer(request.fromAccountId(), request.toAccountId(), authentication.getName(),
				request.amount(), idempotencyKey);
	}

	@ExceptionHandler(AccountException.class)
	public ResponseEntity<Map<String, String>> handleAccountException(final AccountException exception)
	{
		return ResponseEntity.status(exception.getStatus()).body(Map.of(ResponseKeys.ERROR, exception.getMessage()));
	}
}
