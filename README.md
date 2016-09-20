Target player service workflow:

POST  /game/:game-id -> new game - respond with "ready"

-------
For each turn:
POST /game/:game-id/:turn -> deal player cards for turn + current game state, power down response required
this request is performed in order of player start position, so each
subsequent player is aware of which players before it are powering down

GET /game/:game-id/:turn/registers -> the registers to be executed for the turn
Concurrently requested for each player. Timeout set to 5 seconds - if no
request received, all remaining cards will be transferred to the player in
random order.
 to the player with docking-bay-number = (inc player-docking-bay-number)
 
PUT /game/:game-id/:turn -> game state after turn is complete
 if player was powered down this turn, then can respond with powered down response for subsequent turn
 
-------

At the end of the game
DELETE /game/:game-id -> game over, send result
 
Additional services:
/ping -> to check if service can be contacted - can respond with a string message?

