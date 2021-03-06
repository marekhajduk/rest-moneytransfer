package com.marhaj.money.transfer;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import com.marhaj.money.account.Account;
import com.marhaj.money.account.AccountFacade;
import com.marhaj.money.transfer.dto.NotEnoughBalanceException;
import com.marhaj.money.transfer.dto.Transfer;
import com.marhaj.money.transfer.dto.TransferRetryPolicyException;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

public class TransferFacade {
	private static final RetryPolicy defualtPolicy = new RetryPolicy().retryOn(TransferRetryPolicyException.class)
			.withDelay(1, TimeUnit.MILLISECONDS).withMaxRetries(50);
	private TransferRepository trasferRepository;
	private AccountFacade accountFacade;
	private RetryPolicy retryPolicy;

	public TransferFacade() {
		this(new InMemoryTransferRepository(), new AccountFacade(), defualtPolicy);
	}

	public TransferFacade(AccountFacade accountFacade) {
		this(new InMemoryTransferRepository(), accountFacade, defualtPolicy);
	}

	public TransferFacade(TransferRepository transferRepository) {
		this(transferRepository, new AccountFacade(), defualtPolicy);
	}

	public TransferFacade(TransferRepository trasferRepository, AccountFacade accountFacade, RetryPolicy retryPolicy) {
		this.trasferRepository = trasferRepository;
		this.accountFacade = accountFacade;
		this.retryPolicy = retryPolicy;
	}

	public Transfer save(Transfer transfer) {
		requireNonNull(transfer);
		Account from = requireNonNull(accountFacade.account(transfer.getFrom()));
		Account to = requireNonNull(accountFacade.account(transfer.getTo()));
		Lock fromLock = from.getLock();
		Lock toLock = to.getLock();

		Failsafe.with(retryPolicy).run(() -> {
			fromLock.lock();
			if (false == toLock.tryLock()) {
				fromLock.unlock();
				throw new TransferRetryPolicyException(transfer, retryPolicy);
			}
			BigDecimal amount = transfer.getAmount();
			BigDecimal fromBalance = from.getBalance();
			BigDecimal toBalance = to.getBalance();

			BigDecimal fromNewBalance = fromBalance.subtract(amount);

			if (fromNewBalance.signum() < 0) {
				throw new NotEnoughBalanceException(from, amount);
			}

			from.setBalance(fromNewBalance);
			to.setBalance(toBalance.add(amount));
			accountFacade.save(from);
			accountFacade.save(to);
			trasferRepository.save(transfer);
			toLock.unlock();
			fromLock.unlock();
		});

		return transfer;
	}

	public List<Transfer> transfers() {
		return trasferRepository.findAll();
	}
	
	public void delete() {
		trasferRepository.delete();
	}
}
