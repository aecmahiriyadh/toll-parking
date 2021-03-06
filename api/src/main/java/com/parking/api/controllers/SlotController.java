package com.parking.api.controllers;

import com.parking.api.models.*;
import com.parking.api.exceptions.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// import org.springframework.jdbc.core.BeanPropertyRowMapper;
// import org.springframework.dao.EmptyResultDataAccessException;
// import org.springframework.web.bind.annotation.PathVariable;
// import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.data.domain.Example;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import java.util.List;
import java.util.Optional;
import java.util.Random;

@RestController
@Api(description = "Set of endpoints for Listing, Booking, and Releasing parking slots.")
public class SlotController {

    /** a direct connection to the backend database */
    @Autowired
    private JdbcTemplate jtm;

    /** auto-map to the tables in the database */
    @Autowired
    private SlotRepository slotRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private BillingRepository billingRepository;

    Logger logger = LoggerFactory.getLogger(SlotController.class);

    @GetMapping("/ping")
    @ApiOperation("A simple endpoint to check the sanity of APIs")
    public String ping() {
        return "alive";
    }

    /**
     * list all the parking slots
     *
     * @return
     */
    @GetMapping("/slot/list")
    @ApiOperation("Returns list of parking slots in the system.")
    public @ResponseBody Iterable<Slot> listSlots() {
        return slotRepository.findAll();
    }

    /**
     *  check the current status of the slot, i.e. available or not
     * @param slotId
     * @return
     */
    @GetMapping("/slot/status")
    @ApiOperation("Get the status on a particular parking slot.")
    public Slot getSlotStatus(
        @ApiParam("ID of the parking slot, e.g. 1")
            @RequestParam(value = "slotId") Long slotId) {

        Slot slotQuery = new Slot();
        slotQuery.setId(slotId);;
        Example<Slot> slotQueryExample = Example.of(slotQuery);

        Optional<Slot> result = slotRepository.findOne(slotQueryExample);
        if (result.isEmpty()) {
            throw new SlotNotFoundException(slotId);
        }
        return result.get();
    }

    /**
     *  Request a parking slot, if available, park the car.
     * @param type
     * @param carId
     * @return
     */
    @PostMapping("/slot/park")
    @ApiOperation("Reserve a parking slot, given a car.")
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Car is parked successfully."),
        @ApiResponse(code = 404, message = "Not suitable parking slot is available.") })
    public Reservation bookSlot(
        @ApiParam("Type of car to park, e.g. sedan, EV-20KW, EV-50KW.")
            @RequestParam(value = "type") String type,
        @ApiParam("ID of the car, e.g. EV-XXX-ZZ.")
            @RequestParam(value = "carId") String carId) {

        logger.debug(String.format("[booking request] type: %s, carId: %s", type, carId));

        // Case 1). check if the car has already been parked, to avoid double booking.
        Reservation reservationQuery = new Reservation();
        reservationQuery.setCarId(carId);
        Example<Reservation> reservationQueryExample = Example.of(reservationQuery);
        List<Reservation> reservations = reservationRepository.findAll(reservationQueryExample);
        if (reservations.size() > 0) {
            logger.info(String.format("Reservation for car (%s) exists already.", carId));
            return reservations.get(0);
        }

        // Case 2). a new incoming car, check if there is any slot available.
        Slot slotQuery = new Slot();
        slotQuery.setType(type);
        slotQuery.setIsAvailable(true);
        Example<Slot> slotQueryExample = Example.of(slotQuery);
        List<Slot> slots = slotRepository.findAll(slotQueryExample);
        if (slots.size() == 0) {
            throw new NoSlotAvailableException(type);
        }

        // Case 3). create a new reservation for the new incoming car
        // randomly pick a slot
        Random rand = new Random();
        Slot chosenSlot = slots.get(rand.nextInt(slots.size()));

        // A runtime exception would be thrown here if there is potential concurrency.
        // But the integrity of the database still holds,
        //   thanks to the UNIQUE constraints and transaction rollback.
        return createReservationAndUpdateSlot(chosenSlot, carId);
    }

    /**
     *  Create a new reservation and update the status of the slot.
     *  In order for the reservation to succeed, both the carId and slotId
     *    should be unique to avoid any duplicated reservation.
     * @param chosenSlot
     * @param carId
     * @return
     */
    @Transactional
    public Reservation createReservationAndUpdateSlot(Slot chosenSlot, String carId) {
        // Transactional operation 1). create a new reservation
        // create a reservation record
        Reservation newReservation = new Reservation(
            chosenSlot.getId(), carId, chosenSlot.getBillingPolicy());
        // Note: if there are more than one reservation for the same car
        //   or there are more than one reservation for the same parking slot,
        //   the insertion should fail here.
        reservationRepository.save(newReservation);

        // Transactional operation 2). update the status of the slot
        // mark the slot as reserved
        chosenSlot.setIsAvailable(false);
        slotRepository.save(chosenSlot);

        // If all operations are ok, return the newly-created reservation
        return newReservation;
    }


    /**
     *  Check out the parking slot, and make a billing.
     * @param carId
     * @return
     */
    @PutMapping("/slot/leave")
    @ApiOperation("Release the parking slot that is occupied by a given car.")
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Slot is released successfully."),
        @ApiResponse(code = 404, message = "No reservation for the given car is found.") })
    public Billing leaveSlot(
        @ApiParam("ID of the car, e.g. EV-XXX-ZZ.")
            @RequestParam(value = "carId") String carId) {

        logger.debug(String.format("[checkout request] carId: %s", carId));

        // Case 1). check if the car is indeed parked.
        Reservation reservationQuery = new Reservation();
        reservationQuery.setCarId(carId);
        Example<Reservation> reservationQueryExample = Example.of(reservationQuery);
        Optional<Reservation> reservations = reservationRepository.findOne(reservationQueryExample);

        if (reservations.isEmpty()) {
            logger.error(String.format("Cannot find reservation for car (%s)!", carId));
            throw new ReservationNotFoundException(carId);
        }

        // Case 2). checkout the car, and make a billing record.
        // We should not have more than one reservation.
        Reservation reservation = reservations.get();

        // remove the reservation and free the slot.
        /**
         * Note: if the following transaction fails, the changes would be reverted.
         *  And more importantly the customer will not be factured twice.
         */
        removeReservationAndUpdateSlot(reservation);

        // Note: the cost is calculated inside the Billing bean class.
        Billing billing = new Billing(reservation.getSlotId(), reservation.getCarId(),
                reservation.getCheckinDatetime(), reservation.getBillingPolicy());
        billing = billingRepository.save(billing);  // save the billing record to db

        // return the billing record.
        return billing;
    }

    @Transactional
    public void removeReservationAndUpdateSlot(Reservation reservation) {
        // If the reservation has already been billed, this operation will fail.
        reservationRepository.delete(reservation);
        Optional<Slot> bookedSlot = slotRepository.findById(reservation.getSlotId());
        if (! bookedSlot.isPresent()) {
            logger.error(String.format("Cannot find the slot ()!", reservation.getId()));
        } else {
            // make the reserved slot available again
            Slot slot = bookedSlot.get();
            slot.setIsAvailable(true);
            slotRepository.save(slot);
        }
    }

    // @RequestMapping("/slot/status/{slotId}")
    // public ParkingSlot getSlotStatus(@PathVariable Long slotId) {
    //     String sql = "SELECT * FROM SLOT WHERE ID = " + slotId;
    //     List<ParkingSlot> result = jtm.query(sql, new BeanPropertyRowMapper<>(ParkingSlot.class));
    //     return result.get(0);
    // }

    @ExceptionHandler(SlotNotFoundException.class)
    public ResponseEntity<String> noSlotFound(SlotNotFoundException e) {
        logger.error(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(NoSlotAvailableException.class)
    public ResponseEntity<String> noSlotAvailable(NoSlotAvailableException e) {
        logger.error(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<String> noReservationFound(ReservationNotFoundException e) {
        logger.error(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

}