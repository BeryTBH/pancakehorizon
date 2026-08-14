![pancakehorizon icon](https://github.com/BeryTBH/pancakehorizon/blob/main/app/src/main/ic_launcher-playstore.png)

# pancakehorizon

pancakehorizon is an Android root utility for lawfully owned Meta Quest devices.
It provides device-owner controls for maintenance, diagnostics, research, and
system administration on rooted or unlocked-bootloader headsets.

it uses the pancake exploit for versions on v2.4 and above, below not tested
this does not work for now

## Features

- Root-on-boot support for compatible devices
- Safe root tweaks and custom LED color selection
- Built-in terminal with root command execution
- Double-tap passthrough repair for affected root setups
- Internet kill switch and root domain blocker
- Wireless ADB with root access
- Frida Server support
- Frida OCMS minimum OS bypass script
- OCMS minimum OS bypass toggle using runtime Horizon OS SDK spoofing
- Magisk Zygisk toggle repair
- Horizon Feed and social panel interceptor
- USB notification interceptor for MTP enablement
- Meta telemetry disablement
- No-controller requirement toggle for VR applications
- Newer game compatibility toggle for older headset firmware
- System hang mitigation for restricted-network boot scenarios
- OS update monitor that cancels pending update-engine activity
- Build-type spoofing for Dogfood and ShellDebug workflows
- CPU monitoring, minimum-frequency control, and governor selection
- GPU monitoring and minimum/maximum frequency controls
- App manager for installation, launch, and Dogfood Hub control
- Default Meta app auto-install prevention

## Installation

Download the latest APK from the project releases page and sideload it onto a
compatible headset. Grant root access when prompted; most features require root
or an unlocked-bootloader environment.

## Frida

The repository includes Frida Server and host-loaded Frida scripts under
`frida/`. Start Frida Server from the app, then load scripts from a host machine
with the Frida CLI.

See [frida/README.md](frida/README.md) for the OCMS minimum OS bypass script.

## Legal Notice

This project is an independent reverse-engineering effort intended solely to
achieve interoperability with lawfully obtained Meta Quest hardware and
software. Under California's Uniform Trade Secrets Act, reverse engineering or
independent derivation alone is not an "improper means" (California Civil Code
§ 3426.1(a)). U.S. copyright law also permits qualifying reverse engineering of
lawfully obtained computer programs when necessary to achieve interoperability
(17 U.S.C. § 1201(f)).

The maintainers' position is that this project does not violate § 1201 because
it is an independently created program whose purpose is to exchange information
with, and use information exchanged by, Quest software; it identifies and
implements only the protocol elements necessary for that interoperability; and it
does not copy or distribute Meta's copyrighted program code. Section 1201(f)(1)
permits the necessary identification and analysis by a person who has lawfully
obtained the right to use the analyzed program, § 1201(f)(2) permits necessary
interoperability-enabling means, and § 1201(f)(3) permits sharing the resulting
information and means solely to enable interoperability, so long as the acts are
noninfringing and otherwise lawful.

This notice is not legal advice and does not authorize breach of contract,
copyright infringement, unauthorized access, circumvention beyond an applicable
statutory exception, or any other unlawful conduct. Users are responsible for
ensuring that their use complies with all applicable laws and agreements.

This project is not affiliated with or endorsed by Meta Platforms, Inc. or its
affiliates. Meta, Meta Quest, Quest Link, and other product names or trademarks
are the property of their respective owners. To the maximum extent permitted by
law, the software is provided "as is", without warranties of any kind, express
or implied. The project's copyright holders, maintainers, contributors, and any
person or entity involved in creating, publishing, distributing, or documenting
the software accept no liability and assume no responsibility for any damage,
loss, claim, or other consequence arising from its use or misuse.

## Credits

- [zhuowei/cheese-app](https://github.com/zhuowei/cheese-app) for much of the
  app code
- [scottyab/rootbeer](https://github.com/scottyab/rootbeer) for root detection
- [zhuowei/cheese](https://github.com/zhuowei/cheese) for the exploit backend
- Project Zero for adrenaline
- m-y-mo for `adreno_user`
- Mesa for Freedreno
- Longterm Security for shellcode
- topjohnwu and the Magisk developers for Magisk
- Rosie and Mandi in the FreeXR Discord for block list additions
