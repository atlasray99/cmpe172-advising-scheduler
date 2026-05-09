package com.advisingscheduler.service;

import com.advisingscheduler.model.Appointment;
import com.advisingscheduler.model.AvailabilitySlot;
import com.advisingscheduler.repository.AppointmentRepository;
import com.advisingscheduler.repository.AvailabilitySlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class AppointmentService {

    private static final Logger logger = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointmentRepository;
    private final AvailabilitySlotRepository slotRepository;
    private final BookingMetricsService metrics;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              AvailabilitySlotRepository slotRepository,
                              BookingMetricsService metrics) {
        this.appointmentRepository = appointmentRepository;
        this.slotRepository = slotRepository;
        this.metrics = metrics;
    }

    public List<Appointment> getAllAppointments() {
        logger.info("Fetching all appointments");
        return appointmentRepository.findAllDetailed();
    }

    public Optional<Appointment> getAppointmentById(int appointmentId) {
        logger.debug("Looking up appointment {}", appointmentId);
        return appointmentRepository.findByIdDetailed(appointmentId);
    }

    @Transactional
    public int bookAppointment(int clientId, int slotId, int serviceId, String notes) {
        logger.info("Attempting to book slot {} for client {}", slotId, clientId);

        try {
            return metrics.timeBooking(() -> {

                Optional<AvailabilitySlot> slotOpt = slotRepository.findById(slotId);
                if (slotOpt.isEmpty()) {
                    throw new IllegalArgumentException("Slot not found: " + slotId);
                }

                AvailabilitySlot slot = slotOpt.get();
                if (slot.isBooked()) {
                    logger.warn("Slot {} is already booked — rejecting request from client {}", slotId, clientId);
                    metrics.recordBookingConflict();
                    throw new IllegalStateException("Slot " + slotId + " is already booked");
                }

                // Optimistic locking: attempt to mark slot as booked with version check
                int rowsUpdated = slotRepository.markAsBooked(slotId, slot.getVersion());
                if (rowsUpdated == 0) {
                    logger.warn("Booking conflict for slot {} — optimistic lock failed (version mismatch)", slotId);
                    metrics.recordBookingConflict();
                    throw new IllegalStateException("Booking conflict — this slot was just taken. Please try another slot.");
                }

                int appointmentId = appointmentRepository.insert(clientId, slotId, serviceId, notes);
                logger.info("Successfully booked appointment {} for client {} on slot {}", appointmentId, clientId, slotId);
                metrics.recordBookingCompleted();
                return appointmentId;

            });
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error during booking", e);
        }
    }

    @Transactional
    public void cancelAppointment(int appointmentId) {
        logger.info("Attempting to cancel appointment {}", appointmentId);

        Optional<Appointment> apptOpt = appointmentRepository.findByIdDetailed(appointmentId);
        if (apptOpt.isEmpty()) {
            throw new IllegalArgumentException("Appointment not found: " + appointmentId);
        }

        Appointment appt = apptOpt.get();
        if (!"BOOKED".equals(appt.getStatus())) {
            logger.warn("Cannot cancel appointment {} — current status is {}", appointmentId, appt.getStatus());
            throw new IllegalStateException("Only BOOKED appointments can be cancelled. Current status: " + appt.getStatus());
        }

        int rowsUpdated = appointmentRepository.updateStatus(appointmentId, "CANCELLED");
        if (rowsUpdated == 0) {
            throw new IllegalStateException("Failed to cancel appointment — it may have already been cancelled.");
        }

        slotRepository.markAsAvailable(appt.getSlotId());
        logger.info("Cancelled appointment {} and released slot {}", appointmentId, appt.getSlotId());
        metrics.recordBookingCancelled();
    }
}
