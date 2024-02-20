# TimedRankup

Thanks for choosing TimedRankup for your server needs!

## Overview
This Mod allows for you to define groups that will be used to rank up players based on their playtime. You will need to ensure that you have LuckPerms installed in conjunction with this mod.

## Important Notes
- Fabric 0.92.0+1.20.1
- Loader Version 0.14.25
- Minecraft Version 1.20.1

**ENSURE THAT YOUR GROUPS IN LUCKPERMS MATCH THE RANKS YOU ARE ADDING TO THE CONFIG/COMMANDS, THESE ARE CASE SENSITIVE.**

## In-game Commands

**Non-OP Commands**
- `/playtime` - Displays your playtime
- `/playtime <name>` - Displays others' playtime
- `/timedrankup ranklist` - Displays ranks that can be achieved

**OP-Only Commands**
- `/timedrankup addrank <name> <threshold in seconds>` - Adds a rank to the configuration file with the specified name and playtime threshold
- `/timedrankup updaterank <old name> <new name> <threshold in seconds>` - Updates existing ranks' names and threshold
- `/timedrankup removerank <name>` - Removes existing ranks' from the configuration
- `/timedrankup forceupgrade` - Forces the mod to check global playtimes and issue ranks to online players

## Additional Features

**Group Exclusions**
There is a .json file located in `/config/TimedRankup` designated as `exclusions.json`, you can add groups from LP to exclude users' within these groups from being affected by the mod entirely. For example, you may not want staff to be demoted to these ranks.

## To-do List
- Implement Permissions for command usage instead of just OP based permissions.