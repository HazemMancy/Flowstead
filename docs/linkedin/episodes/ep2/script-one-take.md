# Episode 2 — one-take script for Gemini TTS (Google AI Studio)

The model reads bracketed cues aloud, so the script is plain text and every sentence's expression is in the style
instructions, numbered and quoted by its first words. One audio file; the ten paragraphs are the ten chapters, and the
two-second silences between them are the cut points. Voice: **Charon** (Informative), temperature about 0.8.

## Style instructions

```
You are Sam, a senior process engineer with years on gas plants, showing a younger colleague a new simulation tool over their shoulder. Warm, confident and unhurried; a measured conversational pace, about 140 words a minute; never salesy, never loud. Say every number clearly and slightly slower than the words around it. Read "N P S" as three letters and "V-100" as "V one hundred". Pause briefly at full stops. Between paragraphs, leave two full seconds of silence.

Read each sentence with this expression:
Paragraph 1:
1. "Welcome back to Getting Started…" — warmly.
2. "In episode one, we built…" — friendly.
3. "Today, we take that gas…" — confident.
4. "And at every step, we'll…" — reassuring.
Paragraph 2:
5. "A real well doesn't make…" — thoughtful.
6. "So first, in the Basis…" — matter-of-fact.
7. "Same conditions as before: thirty…" — steady, each number clear.
8. "Now it's a gas, a…" — slightly amused.
Paragraph 3:
9. "Next, the choke." — confident.
10. "We drop in a valve…" — matter-of-fact.
11. "Solve." — brisk, after a short beat.
12. "The gas leaves at eighteen…" — emphasis on the number.
13. "That's twelve degrees of Joule–Thomson…" — explaining.
14. "And the enthalpy in equals…" — quietly pleased.
Paragraph 4:
15. "So, where does that put…" — curious.
16. "Let's trace the phase envelope…" — inviting.
17. "The envelope reaches a cricondenbar…" — explaining.
18. "Our stream sits inside it…" — matter-of-fact.
19. "And here's the line that…" — serious.
20. "Hydrates form below fourteen degrees…" — emphasis on the number.
21. "We're at eighteen: only four…" — concerned, after a short beat.
22. "Put twenty weight percent methanol…" — thoughtful.
23. "Good to know before winter." — wry.
Paragraph 5:
24. "Now, the inlet separator." — confident.
25. "A three-phase vessel, with three…" — matter-of-fact.
26. "Solve." — brisk, after a short beat.
27. "Eighteen tonnes an hour of…" — steady, each number clear.
28. "And every compound balances: what…" — quietly pleased.
Paragraph 6:
29. "How big does it need…" — curious.
30. "Open the Design tab, and…" — matter-of-fact.
31. "Flowstead sizes it at one…" — emphasis on the numbers.
32. "The Souders–Brown K is zero…" — explaining.
33. "The levels, the oil–water interface…" — satisfied.
Paragraph 7:
34. "The gas now needs a…" — confident.
35. "In the stream's analysis, open…" — matter-of-fact.
36. "It picks N P S…" — emphasis on the size.
37. "But look at the pressure…" — thoughtful, after a short beat.
38. "Over two kilometres, that line…" — slightly amused.
39. "So we add a third…" — decisive.
40. "N P S six: seven…" — pleased.
Paragraph 8:
41. "Finally, safety." — serious.
42. "If there's a fire or…" — grave.
43. "We give it its design…" — matter-of-fact.
44. "Flowstead takes the target from…" — explaining.
45. "It sizes the orifice at…" — confident.
46. "And it reaches six point…" — emphasis on the time.
47. "The gas cools to minus…" — thoughtful.
48. "That's the number your material…" — knowing.
Paragraph 9:
49. "Everything we did is in…" — warmly.
50. "And the same case opens…" — friendly.
Paragraph 10:
51. "That's episode two." — warmly.
52. "Try it on a case…" — inviting.
53. "See you in the next…" — cheerful.
```

## Script

```
Welcome back to Getting Started with Flowstead. In episode one, we built a well gas and read its properties. Today, we take that gas where it really goes: through the choke, into an inlet separator, and out into a line. And at every step, we'll check that the numbers hold up.

A real well doesn't make dry gas. So first, in the Basis, we add water, and give the well stream seven mole percent of it. Same conditions as before: thirty degrees Celsius, seventy bar absolute, one thousand kilogram moles an hour. Now it's a gas, a condensate and free water, all in one pipe.

Next, the choke. We drop in a valve, connect it, and set the outlet to forty bar absolute. Solve. The gas leaves at eighteen degrees Celsius. That's twelve degrees of Joule–Thomson cooling, about zero point four degrees per bar. And the enthalpy in equals the enthalpy out, exactly as a valve should.

So, where does that put us? Let's trace the phase envelope, with the hydrate curve switched on. The envelope reaches a cricondenbar of one hundred and twenty-five bar, and a cricondentherm of forty-six degrees. Our stream sits inside it: two phase, with free water. And here's the line that matters. Hydrates form below fourteen degrees at forty bar. We're at eighteen: only four degrees of margin. Put twenty weight percent methanol in the water, and the hydrate line drops seven degrees, to about seven. Good to know before winter.

Now, the inlet separator. A three-phase vessel, with three outlets: gas, condensate and water. Solve. Eighteen tonnes an hour of gas, nine hundred and fifty-eight kilograms an hour of condensate, and twelve hundred and fifty-six kilograms an hour of water. And every compound balances: what goes in comes out, even the gas dissolved in the water.

How big does it need to be? Open the Design tab, and choose horizontal. Flowstead sizes it at one point six eight metres, by eight point four metres, tangent to tangent. The Souders–Brown K is zero point one one four metres per second, so the gas may move at zero point five one, and it actually moves at zero point four three. The levels, the oil–water interface and the nozzles come with it: an eight-inch inlet, and a five-inch gas outlet.

The gas now needs a line, two kilometres of it. In the stream's analysis, open line sizing: design mode, schedule forty, and our criteria, eighteen metres per second, and a rho v squared of fifteen thousand. It picks N P S four, at seventeen metres per second. But look at the pressure drop: eighteen bar per kilometre. Over two kilometres, that line would eat most of our pressure. So we add a third limit, two and a half bar per kilometre, and size again. N P S six: seven metres per second, two point one bar per kilometre, and three point nine bar over the whole line.

Finally, safety. If there's a fire or a leak, V-100 must blow down. We give it its design pressure, fifty bar gauge. Flowstead takes the target from API five twenty-one: the lower of six point nine bar gauge, and half the design pressure. It sizes the orifice at sixteen millimetres. And it reaches six point nine bar gauge in fourteen and a half minutes, inside the fifteen. The gas cools to minus fifteen degrees on the way down, while the wall stays above nine. That's the number your material selection will ask about.

Everything we did is in the calculation report, every number straight from DWSIM. And the same case opens on your phone, for when the question comes up on site.

That's episode two. Try it on a case you know, and tell me what you find. See you in the next one.
```
