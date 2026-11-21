/**
 * What HooriDB sends to servers, as it goes: {@link dev.hoori.db.core.log.Journal}, the one seam
 * every statement and command of core passes through, records each {@link
 * dev.hoori.db.core.log.Sent} for the recorder its caller bound, with a password masked before the
 * entry is made.
 */
package dev.hoori.db.core.log;
