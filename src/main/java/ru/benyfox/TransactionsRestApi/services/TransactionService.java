package ru.benyfox.TransactionsRestApi.services;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import ru.benyfox.TransactionsRestApi.dto.Transaction.TransactionDTO;
import ru.benyfox.TransactionsRestApi.enums.ExpenseCategory;
import ru.benyfox.TransactionsRestApi.exceptions.Transaction.TransactionNotCreatedException;
import ru.benyfox.TransactionsRestApi.exceptions.Transaction.TransactionNotFoundException;
import ru.benyfox.TransactionsRestApi.models.Limits.Limit;
import ru.benyfox.TransactionsRestApi.models.Transaction;
import ru.benyfox.TransactionsRestApi.repositories.jpa.TransactionRepository;
import ru.benyfox.TransactionsRestApi.util.TransactionValidator;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@AllArgsConstructor
@Slf4j
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final TransactionValidator transactionValidator;
    private final LimitsService limitsService;
    private final ModelMapper modelMapper;

    public List<Transaction> findAll() {
        return transactionRepository.findAll();
    }

    public TransactionDTO findOne(int id) {
        return convertToTransactionDTO(transactionRepository.findById(id).orElseThrow(TransactionNotFoundException::new));
    }

    public List<TransactionDTO> findExceeded(String accountNumber, ExpenseCategory category) {
            Limit limit = limitsService.findOneByAccountName(category, accountNumber);

            OffsetDateTime limitValidFrom = limit.getLimitDatetime().minusSeconds(1); // Adjust as needed
            List<Transaction> transactions = transactionRepository
                    .findExceededByDate(accountNumber, limitValidFrom);

            log.info(transactions.toString());

            long total = limit.getLimitSum();
            Deque<Transaction> exceededTransactions = new ArrayDeque<>();
            Iterator<Transaction> it = transactions.iterator();
            while (it.hasNext() && total > 0) {
                Transaction transaction = it.next();
                total -= transaction.getSum();
                if (total < 0) {
                    exceededTransactions.addFirst(transaction);
                }
            }

            return exceededTransactions.stream()
                    .map(this::convertToTransactionDTO)
                    .collect(Collectors.toList());
    }


    @Transactional
    public void save(TransactionDTO transactionDTO, BindingResult bindingResult) {

        Transaction transaction = convertToTransaction(transactionDTO);
        transaction.setDatetime(OffsetDateTime.now(ZoneOffset.UTC));

        if (bindingResult.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder();
            List<FieldError> errors = bindingResult.getFieldErrors();

            for (FieldError error : errors) {
                errorMessage
                        .append(error.getField())
                        .append(" - ")
                        .append(error.getDefaultMessage())
                        .append(";");
            }
            throw new TransactionNotCreatedException(errorMessage.toString());
        }

        transactionRepository.save(transaction);

        Limit currentLimit = limitsService
                .findOneByAccountName(transaction.getExpenseCategory(), transaction.getAccountFrom());


        // TODO: сделать конвертацию валют транзакций в доллары (валюта лимита)
        long total = transactionRepository
                .findSumByDate(transaction.getAccountFrom(), currentLimit.getLimitDatetime());

        if (total >= currentLimit.getLimitSum()) currentLimit.setLimitExceeded(true);
    }

    public List<TransactionDTO> getTransactionsList() {
        return this.findAll().stream().map(this::convertToTransactionDTO)
                .collect(Collectors.toList());
    }

    private Transaction convertToTransaction(TransactionDTO transactionDTO) {
        return modelMapper.map(transactionDTO, Transaction.class);
    }

    private TransactionDTO convertToTransactionDTO(Transaction transaction) {
        return modelMapper.map(transaction, TransactionDTO.class);
    }
}
