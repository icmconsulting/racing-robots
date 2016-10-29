Target player service workflow

POST  /game/:game-id -> new game - respond with "ready"
- If no response received, then removed from game 

-------
For each turn:
POST /game/:game-id/:turn -> deal player cards for turn + locked registers + number of cards expected to be returned 
                             + player state + other players states
- returns -> power down response + the registers to be executed for the turn
- not called if powered down
- this request is performed in order of player start position, so each
- Timeout set to 5 seconds - if no request received, all remaining cards will be transferred to the player in
random order.
 to the player with docking-bay-number = (inc player-docking-bay-number)
  
PUT /game/:game-id/:turn -> game state after turn is complete
 if player was powered down this turn, then can respond with powered down response for subsequent turn
 
-------

At the end of the game
DELETE /game/:game-id -> game over, send result
 
Additional services:
/profile -> player and robot profile - doubles as a ping service:
    team-name, robot-name, home-team-colour, away-team-colour (for clashes), logo (in base64 png), message


Test harness requirements:
- View all boards (except the one used in the grand final of the tournament)
- Test game
    - needs:
        - port number of test server
        - (profile service to work)
        - select bots
        - select board
    - user controls game progression
    - user can step back to previous turn and rerun with same dealt cards (or with new ones)
    - show full message log


