
/* schema for tables in H2 database */
DROP TABLE IF EXISTS SLOT;
CREATE TABLE SLOT(
    ID INT PRIMARY KEY AUTO_INCREMENT,
    TYPE VARCHAR(255),
    BILLING_POLICY INT,
    IS_AVAILABLE BOOLEAN);


DROP TABLE IF EXISTS RESERVATION;

/*
 *  Both the carId and slotId should be unique, to avoid the double booking.
 */
CREATE TABLE RESERVATION(
    ID INT PRIMARY KEY AUTO_INCREMENT,
    SLOT_ID INT UNIQUE,
    CAR_ID VARCHAR(255) UNIQUE,
    BILLING_POLICY INT,
    CHECKIN_DATETIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP);


DROP TABLE IF EXISTS BILLING;
CREATE TABLE BILLING(
    ID INT PRIMARY KEY AUTO_INCREMENT,
    SLOT_ID INT,
    CAR_ID VARCHAR(255),
    CHECKIN_DATETIME TIMESTAMP,
    CHECKOUT_DATETIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    BILLING_POLICY INT,
    BILLING_AMOUNT DOUBLE);
