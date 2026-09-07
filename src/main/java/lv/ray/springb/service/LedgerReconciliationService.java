package lv.ray.springb.service;

import lv.ray.springb.dto.ReconciliationResult;

import java.util.List;


public interface LedgerReconciliationService
{
	ReconciliationResult reconcileAccount(Long accountId);

	List<ReconciliationResult> reconcileAllAccounts();
}
