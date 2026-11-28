'use strict';

// Supply desktop OAuth credentials via runtime environment variables.
module.exports = {
  desktopClientId: process.env.RUMBO_DESKTOP_CLIENT_ID || '',
  desktopClientSecret: process.env.RUMBO_DESKTOP_CLIENT_SECRET || '',
  webClientId: process.env.RUMBO_WEB_CLIENT_ID || '',
};
