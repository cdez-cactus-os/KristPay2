package pw.lemmmy.kristpay.economy;

import lombok.Getter;
import lombok.val;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.service.context.Context;
import org.spongepowered.api.service.economy.Currency;
import org.spongepowered.api.service.economy.account.Account;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.economy.transaction.TransactionTypes;
import org.spongepowered.api.service.economy.transaction.TransferResult;
import org.spongepowered.api.text.Text;
import pw.lemmmy.kristpay.KristPay;
import pw.lemmmy.kristpay.Utils;
import pw.lemmmy.kristpay.krist.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Getter
public class KristAccount implements UniqueAccount {
	private String owner;
	private Wallet depositWallet;
	private int balance = 0;
	private int unseenDeposit = 0;
	private int unseenTransfer = 0;
	private int welfareCounter = 0;
	private Instant welfareLastPayment = Instant.EPOCH;
	private int welfareAmount = -1;
	
	private boolean needsSave = false;
	
	public KristAccount(String owner) {
		this.owner = owner;
		this.depositWallet = new Wallet(Utils.generatePassword());
		this.balance = 0;
		needsSave = true;
	}
	
	public KristAccount(String owner,
						Wallet depositWallet,
						int balance,
						int unseenDeposit,
						int unseenTransfer,
						int welfareCounter,
						Instant welfareLastPayment,
						int welfareAmount) {
		this.owner = owner;
		this.depositWallet = depositWallet;
		this.balance = balance;
		this.unseenDeposit = unseenDeposit;
		this.unseenTransfer = unseenTransfer;
		this.welfareCounter = welfareCounter;
		this.welfareLastPayment = welfareLastPayment;
		this.welfareAmount = welfareAmount;
	}
	
	public KristAccount setUnseenDeposit(int unseenDeposit) {
		this.unseenDeposit = unseenDeposit;
		needsSave = true;
		return this;
	}
	
	public KristAccount setUnseenTransfer(int unseenTransfer) {
		this.unseenTransfer = unseenTransfer;
		needsSave = true;
		return this;
	}
	
	public KristAccount incrementWelfareCounter() {
		welfareCounter++;
		welfareLastPayment = Instant.now();
		needsSave = true;
		return this;
	}
	
	public KristAccount setWelfareAmount(int welfareAmount) {
		this.welfareAmount = welfareAmount;
		needsSave = true;
		return this;
	}
	
	@Override
	public Text getDisplayName() {
		return Text.of(owner);
	}
	
	@Override
	public BigDecimal getDefaultBalance(Currency currency) {
		return BigDecimal.valueOf(KristPay.INSTANCE.getConfig().getEconomy().getStartingBalance());
	}
	
	@Override
	public boolean hasBalance(Currency currency, Set<Context> contexts) {
		return true;
	}
	
	@Override
	public BigDecimal getBalance(Currency currency, Set<Context> contexts) {
		return BigDecimal.valueOf(balance);
	}
	
	@Override
	public Map<Currency, BigDecimal> getBalances(Set<Context> contexts) {
		val balances = new HashMap<Currency, BigDecimal>();
		balances.put(KristPay.INSTANCE.getCurrency(), getBalance(KristPay.INSTANCE.getCurrency()));
		return balances;
	}
	
	@Override
	public Map<Currency, BigDecimal> getBalances() {
		return getBalances(null);
	}
	
	@Override
	public Map<Currency, TransactionResult> resetBalances(Cause cause, Set<Context> contexts) {
		TransactionResult result = resetBalance(KristPay.INSTANCE.getCurrency(), cause, contexts);
		
		Map<Currency, TransactionResult> map = new HashMap<>();
		map.put(KristPay.INSTANCE.getCurrency(), result);
		return map;
	}
	
	@Override
	public TransactionResult resetBalance(Currency currency, Cause cause, Set<Context> contexts) {
		return setBalance(currency, getDefaultBalance(currency), cause, contexts);
	}
	
	@Override
	public TransactionResult deposit(Currency currency, BigDecimal amount, Cause cause, Set<Context> contexts) {
		return setBalance(currency, BigDecimal.valueOf(balance + amount.intValue()), cause, contexts);
	}
	
	@Override
	public TransactionResult withdraw(Currency currency, BigDecimal amount, Cause cause, Set<Context> contexts) {
		int amt = amount.intValue();
		int newBalance = balance - amt;
		
		if (newBalance < 0) {
			return new KristTransactionResult(this, amount, contexts, ResultType.ACCOUNT_NO_FUNDS, TransactionTypes
				.WITHDRAW, "Insufficient funds.");
		} else {
			return setBalance(currency, BigDecimal.valueOf(newBalance), cause, contexts);
		}
	}
	
	@Override
	public String getIdentifier() {
		return owner;
	}
	
	@Override
	public Set<Context> getActiveContexts() {
		return null;
	}
	
	@Override
	public UUID getUniqueId() {
		return UUID.fromString(owner);
	}
	
	@Override
	public TransactionResult setBalance(Currency currency, BigDecimal amount, Cause cause, Set<Context> contexts) {
		if (amount.intValue() < 0) { // balance should never be negative
			return new KristTransactionResult(this, amount, contexts, ResultType.FAILED, TransactionTypes.WITHDRAW, null);
		}
		
		int delta = balance - amount.intValue();
		
		if (delta < 0) { // increase in balance - check master wallet can fund it
			int masterBalance = KristPay.INSTANCE.getMasterWallet().getBalance();
			int used = KristPay.INSTANCE.getAccountDatabase().getTotalDistributedKrist();
			
			int available = masterBalance - used;
			int increase = Math.abs(delta);
			
			if (increase > available) {
				return new KristTransactionResult(this, BigDecimal.valueOf(0), contexts, ResultType.FAILED,
					TransactionTypes.DEPOSIT, "Master wallet can't fund this.");
			} else {
				balance = amount.intValue();
				KristPay.INSTANCE.getAccountDatabase().save();
				return new KristTransactionResult(this, BigDecimal.valueOf(increase), contexts, ResultType.SUCCESS, TransactionTypes.DEPOSIT, null);
			}
		} else if (delta > 0) { // decrease in balance
			balance = amount.intValue();
			KristPay.INSTANCE.getAccountDatabase().save();
			return new KristTransactionResult(this, BigDecimal.valueOf(Math.abs(delta)), contexts, ResultType.SUCCESS, TransactionTypes.WITHDRAW, null);
		} else { // no change in balance
			return new KristTransactionResult(this, BigDecimal.valueOf(0), contexts, ResultType.SUCCESS, TransactionTypes.WITHDRAW, null);
		}
	}
	
	@Override
	public TransferResult transfer(Account to,
								   Currency currency,
								   BigDecimal amount,
								   Cause cause,
								   Set<Context> contexts) {
		if (!(to instanceof KristAccount)) {
			return new KristTransferResult(to, this, currency, amount, contexts, ResultType.FAILED, TransactionTypes.TRANSFER, "Recipient does not have an account.");
		}
		
		KristAccount target = (KristAccount) to;
		
		if (amount.intValue() < 0) {
			return new KristTransferResult(to, this, currency, amount, contexts, ResultType.FAILED, TransactionTypes.TRANSFER, "Amount is less than zero.");
		}
		
		if (balance - amount.intValue() < 0) {
			return new KristTransferResult(to, this, currency, amount, contexts, ResultType.ACCOUNT_NO_FUNDS, TransactionTypes.TRANSFER, "Insufficient funds.");
		}
		
		balance -= amount.intValue();
		target.balance += amount.intValue();
		KristPay.INSTANCE.getAccountDatabase().save();
		
		return new KristTransferResult(to, this, currency, amount, contexts, ResultType.SUCCESS, TransactionTypes.TRANSFER, null);
	}
}
