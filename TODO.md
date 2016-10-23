# TODO

## Test Harness
- Show registers for turn
- end of game screen - display winner, hide board
- auto-play game, pause game
- Retry a turn
- Make log messages human readable
- Add other bots
- Player Bot connectors (http, socket with protocol buffers, lambda)
    - players can pick their avatar, name, robot name

## Game viewer / Game
- Ensure locked register cards aren't dealt again next turn
- Ensure cards selected by user are from the cards they were dealt - if caught cheating, destroy robot on first register
- Incrementally show result after each register
- Fix layout for player scoresheet, show player id, show flags touched
- Viewable player archive marker
- Bots destroyed -> explosion sprite
- Use consistent board square pieces (the ones that match the bots)
- show game id

## Boards
- Add new boards