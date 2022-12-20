# UPPAAL ProtoSugar

UPPAAL ProtoSugar – short for "_**Proto**typer for Syntactic **Sugar**_" – allows for quick and cheap prototyping of new language features by means of mapping/rewriting models, thus circumventing the need to spend many hours implementing the full feature in the UPPAAL engine. 

ProtoSugar is a "middleware" that is integrated between the UPPAAL GUI and the UPPAAL engine where it intercepts and rewrites certain commands/responses going between the GUI and engine. The image below shows a simplified overview of ProtoSugar's integration and functionality, where some things are simplified and some are left out (see Section 3 for in-depth explanations).

![](img/ProtoSugarOverview.png)

**Simple image explanation:** The image depicts how an input model or query is intercepted by ProtoSugar and mapped. If the mapping succeeds, the result is sent to the engine, otherwise, errors are returned to the GUI. Next, the engine either returns a successful result or a list of errors. A successful result is simply sent to the GUI, whereas errors are put through the mapping in reverse order to "back-map" them onto their "correct locations" in the original input (since the errors are generate on the mapped input).

**Multiple mappers:** ProtoSugar is both 1) a "mapping framework" and 2) a "mapper orchestrator", since 1) it has facilities for defining individual "Mappers" for every new language feature to prototype, and 2) is allows the user to enable/disable individual mappers. Section 1 contains a list of all available mappers and Section 2 describes each mapper in detail-

The remainder of this document is structured as follows:
- "1. How to use": Explains how to configure ProtoSugar in UPPAAL and basic know-how.
- "2. Mappers in detail": Explains all available mappers in more detail. It also includes examples of why they are suggested as potential features.
- "3. Architecture and extensibility": Explains how ProtoSugat is "put together" and how to implement new mappers.

## 1. How to use




## 2. Mappers in detail

### 2.1 `TxQuan` – Textual Quantifiers

### 2.2 `AutoArr` – Auto Arrays

### 2.3 `PaCha` – Parameterized Channels

### 2.4 `SeComp` – Sequential Composition


## 3. Architecture and extensibility
Kotlin

### How is ProtoSugar structured


### How to make a new mapper