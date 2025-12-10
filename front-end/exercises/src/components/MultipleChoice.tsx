import React from 'react';
import { useRef } from "react";

interface MultipleChoiceProps {
    choices: string[]
    scrambled: boolean
    handleSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
    setStudentAnswer: (studentAnswer: string) => void;
}

// Utility function to shuffle an array
const shuffleArray = (array: string[]) => {
    for (let i = array.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [array[i], array[j]] = [array[j], array[i]];
    }
    return array;
};

const MultipleChoice: React.FC<MultipleChoiceProps> = ({ choices, scrambled, handleSubmit, setStudentAnswer }) => {
    const valuesRef = useRef<string[]>("abcdefg".split('').slice(0, choices.length));
    const shuffleRef = useRef<boolean>(scrambled);
    const hasLongChoice = choices.some(choice => choice.length > 40);
    
    if (shuffleRef.current) {
        valuesRef.current = shuffleArray([...valuesRef.current]);
        shuffleRef.current = false; // Ensures that choices are only shuffled once
    }
    return (
        <form onSubmit={handleSubmit} style={{ display: 'flex', alignItems: 'center' }}>
            <div className="form-check" style={{ flex: 1, borderStyle: 'solid', padding: '15px', paddingLeft: '50px' }}>
                {valuesRef.current.map((value, index) => (
                    <label key={index} style={{ display: 'block', whiteSpace: hasLongChoice ? 'normal' : 'nowrap' }}>
                        <input className="form-check-input" type="radio" required name="multiple_choice" value={value} onChange={(e) => { setStudentAnswer(e.target.value); }} />
                        <span dangerouslySetInnerHTML={{ __html: choices[value.charCodeAt(0) - 97] }} />
                    </label>
                ))}
            </div>
            <div style={{ flex: 1, paddingLeft: '15px', textAlign: 'center' }}>
                <button className="btn btn-primary" type="submit">Submit</button>
            </div>
        </form>
    );
}

export default MultipleChoice;