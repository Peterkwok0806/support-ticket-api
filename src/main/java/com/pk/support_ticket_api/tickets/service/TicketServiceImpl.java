package com.pk.support_ticket_api.tickets.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.categories.service.SlaCalculator;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ForbiddenOperationException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.domain.TicketSpecification;
import com.pk.support_ticket_api.tickets.dto.*;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final TicketStateMachine stateMachine;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final SlaCalculator slaCalculator;
    private final Clock clock;

    @Override
    public TicketResponse createTicket(CreateTicketRequest request, UUID createdBy) {
        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Category not found: " + request.categoryId()));

        Instant createdAt = Instant.now(clock);
        TicketPriority priority = request.priority() != null
            ? request.priority()
            : TicketPriority.MEDIUM;
        Instant slaDeadline = slaCalculator.calculateSlaDueAt(category, priority, createdAt);

        Ticket ticket = new Ticket();
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setCategoryId(request.categoryId());
        ticket.setCreatedBy(createdBy);
        ticket.setPriority(priority);
        ticket.setSlaDeadline(slaDeadline);
        ticket.setStatus(TicketStatus.OPEN);

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketResponse getTicketById(UUID id) {
        Ticket ticket = findTicketById(id);
        return enrichResponse(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TicketSummaryResponse> getTickets(
            TicketFilterRequest filter,
            Pageable pageable
    ) {
        Page<Ticket> page = ticketRepository.findAll(
            TicketSpecification.withFilters(
                filter.statuses(),
                filter.priority(),
                filter.categoryId(),
                filter.assignedTo(),
                filter.createdBy(),
                filter.keyword()
            ),
            pageable
        );

        return PageResponse.from(page, TicketSummaryResponse::from);
    }

    @Override
    public TicketResponse updateTicket(UUID id, UpdateTicketRequest request) {
        Ticket ticket = findTicketById(id);

        if (stateMachine.isFinalState(ticket.getStatus())) {
            throw new ForbiddenOperationException("Cannot update closed ticket");
        }

        if (request.title() != null && !request.title().isBlank()) {
            ticket.setTitle(request.title());
        }

        if (request.description() != null) {
            ticket.setDescription(request.description());
        }

        if (request.categoryId() != null) {
            validateCategoryExists(request.categoryId());
            ticket.setCategoryId(request.categoryId());
        }

        if (request.priority() != null && request.priority() != ticket.getPriority()) {
            ticket.setPriority(request.priority());
            recalculateSlaDeadline(ticket);
        }

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    public TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request) {
        Ticket ticket = findTicketById(id);

        if (!stateMachine.canTransition(ticket.getStatus(), request.status())) {
            throw new ForbiddenOperationException(String.format(
                "Cannot transition from %s to %s",
                ticket.getStatus(),
                request.status()
            ));
        }

        ticket.setStatus(request.status());
        handleStatusSideEffects(ticket, request.status());

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    public TicketResponse assignTicket(UUID id, TicketAssignRequest request) {
        Ticket ticket = findTicketById(id);

        if (stateMachine.isFinalState(ticket.getStatus())) {
            throw new ForbiddenOperationException("Cannot assign closed ticket");
        }

        if (request.assigneeId() != null) {
            validateUserExists(request.assigneeId());
        }

        ticket.setAssignedTo(request.assigneeId());

        Ticket saved = ticketRepository.save(ticket);
        return enrichResponse(saved);
    }

    @Override
    public void deleteTicket(UUID id) {
        Ticket ticket = findTicketById(id);
        ticketRepository.delete(ticket);
    }

    private Ticket findTicketById(UUID id) {
        return ticketRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found: " + id));
    }

    private void validateCategoryExists(UUID categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new BusinessRuleException("Category not found: " + categoryId);
        }
    }

    private void validateUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessRuleException("User not found: " + userId);
        }
    }

    private void recalculateSlaDeadline(Ticket ticket) {
        Category category = categoryRepository.findById(ticket.getCategoryId())
            .orElseThrow(() -> new BusinessRuleException("Category not found"));
        Instant newDeadline = slaCalculator.calculateSlaDueAt(
            category, ticket.getPriority(), ticket.getCreatedAt());
        ticket.setSlaDeadline(newDeadline);
    }

    private void handleStatusSideEffects(Ticket ticket, TicketStatus newStatus) {
        Instant now = Instant.now(clock);

        switch (newStatus) {
            case RESOLVED -> ticket.setResolvedAt(now);
            case CLOSED -> ticket.setClosedAt(now);
            case IN_PROGRESS -> {
                if (ticket.getFirstResponseAt() == null) {
                    ticket.setFirstResponseAt(now);
                }
            }
            default -> { /* no-op */ }
        }
    }

    private TicketResponse enrichResponse(Ticket ticket) {
        TicketResponse response = TicketResponse.from(ticket);

        Category category = categoryRepository.findById(ticket.getCategoryId()).orElse(null);
        String categoryId = category != null ? category.getId().toString() : null;
        String categoryName = category != null ? category.getName() : null;
        CategorySummaryResponse categorySummary = new CategorySummaryResponse(categoryId, categoryName);

        String createdByName = userRepository.findById(ticket.getCreatedBy())
            .map(User::getDisplayName)
            .orElse(null);

        String assignedToName = null;
        if (ticket.getAssignedTo() != null) {
            assignedToName = userRepository.findById(ticket.getAssignedTo())
                .map(User::getDisplayName)
                .orElse(null);
        }

        return new TicketResponse(
            response.id(),
            response.title(),
            response.description(),
            response.status(),
            response.priority(),
            categorySummary,
            createdByName,
            assignedToName,
            response.slaDeadline(),
            response.resolvedAt(),
            response.closedAt(),
            response.firstResponseAt(),
            response.createdAt(),
            response.updatedAt()
        );
    }
}
